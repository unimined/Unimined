package xyz.wagyourtail.unimined.transform.internal.wiring

import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.file.FileCollection
import org.gradle.api.provider.Provider
import xyz.wagyourtail.unimined.api.util.MavenCoords
import xyz.wagyourtail.unimined.api.util.failProblem
import xyz.wagyourtail.unimined.api.util.getCoords
import xyz.wagyourtail.unimined.api.util.reportProblem
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
    /** `fabric`|`jarjar` — absent when nothing overrides it and the
     *  env's loader nests no jars at its version (`include` then fails
     *  at packaging). */
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

        // format: explicit `nestedJars { format }` override → a declared
        // `features { nestedJars("…") }` binding → the format the env's
        // loader reads at its Minecraft/loader version. Production is a
        // claim consumer too — force the idempotent contribution before
        // reading bindings so post-evaluation env construction can't
        // observe the rowless fallback before `loader(...)` is declared.
        val declared: Provider<String> = envSpec?.nestedJars?.format
            ?.orElse(project.provider {
                envSpec.contributeTransformerClaim()
                envSpec.features.bindings["nestedJars"]
            })
            ?: project.provider { null }
        val format = declared.orElse(environment.loaderSpec.flatMap { spec ->
            environment.version.map { mc ->
                LoaderRegistry.resolveBuiltin(spec.name)
                    ?.metadata?.nestedJarsFormat?.invoke(mc, spec.version)
            }
        })

        val metadataFile = descriptor.map { it?.metadata?.fileName }
        val nestedJarsKey = descriptor.map { it?.metadata?.nestedJarsKey }

        val objects = project.objects
        val entries = project.provider {
            val arts = include.incoming.artifacts.resolvedArtifacts.get()

            // filename collisions — two distinct artifacts claiming the
            // same nested entry name silently corrupt the packaged jar
            arts.groupBy { it.file.name }
                .filterValues { v ->
                    v.map { it.file.invariantSeparatorsPath }
                        .distinct().size > 1
                }
                .takeIf { it.isNotEmpty() }?.let { collisions ->
                    project.failProblem(
                        "nested-jars/collision",
                        "unimined: nestedJars: filename collision(s) in " +
                                "'${include.name}' — distinct artifacts " +
                                "sharing a jar name",
                        collisions.entries.joinToString("\n") {
                                    (n, vs) -> "  $n <- " + vs.joinToString(
                                        ", ") { it.file.absolutePath }
                                }
                    )
                }

            arts.forEach { art ->
                val c = runCatching { art.getCoords() }.getOrNull()
                    ?: return@forEach
                // open/dynamic selectors can't become a jarjar
                // `version.range` — the packaged jar's resolvability
                // would silently depend on which version happened to
                // resolve today (MDG JarJar rejects these outright)
                declaredVersionFor(include, c)?.let { declared ->
                    // upper-bound-empty maven range or a dynamic
                    // selector — closed ranges ([v1,v2)) are fine
                    val open = (declared.startsWith("[") ||
                            declared.startsWith("(")) &&
                            (declared.endsWith(",)") ||
                                    declared.endsWith(",]"))
                    val dynamic = declared.endsWith("+") ||
                            declared.startsWith("latest.")
                    if (open || dynamic) project.failProblem(
                        "nested-jars/version-range",
                        "unimined: nestedJars: '$c' declares version " +
                                "'$declared' — open/dynamic selectors " +
                                "can't be expressed as a jarjar " +
                                "version.range",
                        "pin a version or a closed range ([v1,v2))"
                    )
                }
                // remote-resolvable-only coords — consumers' jarjar
                // resolvers can't fetch curse/modrinth artifacts
                if (c.group == "curse.maven" ||
                    c.group == "maven.modrinth") {
                    project.reportProblem(
                        "nested-jars/remote-coords",
                        "nestedJars: '$c' is a CurseForge/Modrinth " +
                                "coordinate",
                        "the packed jar embeds it, but consumers " +
                                "resolving the jarjar range can't " +
                                "fetch from those repos; prefer a " +
                                "maven coordinate"
                    )
                }
            }

            arts.map { art ->
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

    /** The declared version constraint for [coords] on [include], or
     *  null for file deps/non-external dependencies. */
    private fun declaredVersionFor(
        include: Configuration,
        coords: MavenCoords
    ): String? = include.allDependencies
        .filterIsInstance<org.gradle.api.artifacts.ExternalDependency>()
        .firstOrNull {
            it.group == coords.group && it.name == coords.artifact
        }
        ?.version

    /** jarjar's `version.range` — the dependency's *declared*
     *  version constraint when it's a range (`[v]`/`(v)`), else the
     *  open range `[resolved,)` forge writes for fixed versions. */
    private fun versionRangeFor(
        include: Configuration,
        coords: MavenCoords
    ): String {
        val declared = declaredVersionFor(include, coords)
        return if (declared != null &&
            (declared.startsWith("[") || declared.startsWith("("))) declared
        else "[${coords.version},)"
    }

}
