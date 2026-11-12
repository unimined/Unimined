package xyz.wagyourtail.unimined.transform.internal

import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.file.FileCollection
import org.gradle.api.provider.Provider
import xyz.wagyourtail.unimined.api.util.MavenCoords
import xyz.wagyourtail.unimined.api.util.getCoords
import xyz.wagyourtail.unimined.minecraft.MinecraftEnvironment
import xyz.wagyourtail.unimined.remap.NestedJarEntrySpec
import xyz.wagyourtail.unimined.transform.TransformExtension
import xyz.wagyourtail.unimined.transform.loader.LoaderRegistry

/**
 * Resolved `include`-bucket inputs bound for the production jar —
 * handed to [ProductionJarWiring], which feeds them to `remapJar`
 * (non-identity environments) or the plain `Jar` post-pass (identity).
 * Every field is lazy: artifact resolution and binding reads land at
 * execution/input-fingerprint, never configuration.
 */
internal class NestedJarsPlan(
    /** The `include` configuration as a file collection — build-dep
     *  tracking so task outputs declared on `include` order correctly. */
    val files: FileCollection,
    /** Per-artifact coordinate/metadata rows (path-paired to [files]). */
    val entries: Provider<List<NestedJarEntrySpec>>,
    /** `fabric`|`jarjar` — absent when the env declares no binding and
     *  its loader metadata carries no `nestedJarsKey` (feature off). */
    val format: Provider<String>,
    val recompress: Provider<Boolean>,
    val metadataFile: Provider<String>,
    val nestedJarsKey: Provider<String>,
    /** The env's production namespace — nested-jar marker check. */
    val expectedNamespace: Provider<String>
)

internal object NestedJarsWiring {

    fun plan(
        project: Project,
        environment: MinecraftEnvironment,
        minecraft: xyz.wagyourtail.unimined.minecraft.MinecraftExtension,
        transform: TransformExtension?
    ): NestedJarsPlan {
        val include = project.configurations.getByName(
            environment.includeConfigurationName
        )
        val envSpec = transform?.sourceSet(environment.environmentId)
        val descriptor = environment.loaderSpec.map {
            LoaderRegistry.resolveBuiltin(it.name)
        }

        // format: explicit `nestedJars { format }` override → the
        // claim's `features { nestedJars("…") }` binding → the loader
        // descriptor's metadata key (fabric-family loaders always have
        // one, so `include` works without an explicit binding)
        val format = envSpec?.nestedJars?.format
            ?.orElse(project.provider {
                envSpec.features.bindings["nestedJars"]
            })
            ?.orElse(descriptor.map { d ->
                d?.metadata?.nestedJarsKey?.let { "fabric" }
            })
            ?: descriptor.map { d -> d?.metadata?.nestedJarsKey?.let { "fabric" } }

        val metadataFile = descriptor.map { it?.metadata?.fileName }
        val nestedJarsKey = descriptor.map { it?.metadata?.nestedJarsKey }

        val objects = project.objects
        val entries = project.provider {
            include.incoming.artifacts.resolvedArtifacts.get().map { art ->
                objects.newInstance(NestedJarEntrySpec::class.java).apply {
                    jar.fileValue(art.file)
                    // type-marking keys: `group:artifact` for module
                    // deps, the bare file name for file deps — the
                    // coord key wins over the name key, both over the
                    // bucket default
                    val fileName = art.file.name
                    runCatching { art.getCoords() }.getOrNull()?.let { c ->
                        coords.set(c.toString())
                        versionRange.set(versionRangeFor(include, c))
                        val key = "${c.group}:${c.artifact}"
                        envSpec?.nestedJars?.let { nj ->
                            (nj.fmlModTypes.getting(key).orNull
                                ?: nj.fmlModTypes.getting(fileName).orNull
                                ?: nj.fmlModType.orNull)
                                ?.let { fmlModType.set(it) }
                            nj.obfuscated.getting(key).orNull?.let {
                                obfuscated.set(it)
                            }
                        }
                    } ?: envSpec?.nestedJars?.let { nj ->
                        (nj.fmlModTypes.getting(fileName).orNull
                            ?: nj.fmlModType.orNull)
                            ?.let { fmlModType.set(it) }
                        nj.obfuscated.getting(fileName).orNull?.let {
                            obfuscated.set(it)
                        }
                    }
                }
            }
        }

        return NestedJarsPlan(
            files = project.files(include),
            entries = entries,
            format = format,
            recompress = envSpec?.nestedJars?.recompress
                ?: project.provider { true },
            metadataFile = metadataFile,
            nestedJarsKey = nestedJarsKey,
            expectedNamespace = minecraft.modSourceNamespaces(
                environment.environmentId
            ).map { it.singleOrNull() }
        )
    }

    /** jarjar's `version.range` — the dependency's *declared*
     *  version constraint when it's a range (`[v]`/`(v)`), else the
     *  open range `[resolved,)` forge writes for fixed versions. */
    private fun versionRangeFor(
        include: Configuration,
        coords: MavenCoords
    ): String {
        val declared = include.allDependencies
            .filterIsInstance<org.gradle.api.artifacts.ExternalDependency>()
            .firstOrNull {
                it.group == coords.group && it.name == coords.artifact
            }
            ?.version
        return if (declared != null &&
            (declared.startsWith("[") || declared.startsWith("("))) declared
        else "[${coords.version},)"
    }

}
