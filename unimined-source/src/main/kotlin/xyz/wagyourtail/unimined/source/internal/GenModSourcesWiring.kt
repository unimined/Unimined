package xyz.wagyourtail.unimined.source.internal

import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.type.ArtifactTypeDefinition
import org.gradle.api.file.FileCollection
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.SourceSet
import xyz.wagyourtail.unimined.minecraft.MinecraftEnvironment
import xyz.wagyourtail.unimined.minecraft.MinecraftExtension
import xyz.wagyourtail.unimined.remap.RemapExtension
import xyz.wagyourtail.unimined.remap.internal.ModMarkerScan
import xyz.wagyourtail.unimined.remap.spi.RemapSurface
import xyz.wagyourtail.unimined.remap.transform.RemapDependencyTransform
import xyz.wagyourtail.unimined.source.DecompilerRegistry
import xyz.wagyourtail.unimined.source.SourceExtension
import xyz.wagyourtail.unimined.source.spi.DecompileSpec
import xyz.wagyourtail.unimined.source.spi.ModSourcesTransformSpec
import xyz.wagyourtail.unimined.source.task.GenModSourcesTask
import xyz.wagyourtail.unimined.source.task.ModSourceSpec

/**
 * Wires one bound environment's `genModSources`/`gen<Env>ModSources`
 * task: decompiles mod dependencies into sources jars published under
 * their original coordinates in the generated local repo.
 *
 * Unlike `genSources` (one artifact, one task), the mod set is
 * resolution-time and unbounded — decompilation runs as an artifact
 * transform (`remapped-<env>-<bucket>` → `modsrc-decompiled`), chained
 * through the [RemapSurface.SOURCES] pipeline when that surface
 * declares steps, and the task only resolves the final views and
 * publishes the results.
 */
internal object GenModSourcesWiring {

    private val MOD_BUCKETS = listOf(
        MinecraftEnvironment::modImplementationConfigurationName,
        MinecraftEnvironment::modRuntimeOnlyConfigurationName
    )

    fun wire(
        project: Project,
        environment: MinecraftEnvironment,
        @Suppress("unused") sourceSet: SourceSet
    ) {
        project.afterEvaluate {
            wireTask(project, environment)
        }
    }

    private fun wireTask(project: Project, environment: MinecraftEnvironment) {
        val minecraft = project.extensions.getByType(MinecraftExtension::class.java)
        val remap = project.extensions.getByType(RemapExtension::class.java)
        val source = project.extensions.getByType(SourceExtension::class.java)
        val environmentId = environment.environmentId
        // decompiler classpath: the env's library set plus the dev MC
        // artifact — decompilers resolve supertypes/signatures for
        // output quality, and mods' ancestors live in the dev jar
        val libraries = project.files(
            project.configurations.getByName(
                environment.minecraftLibrariesConfigurationName
            ),
            project.provider {
                minecraft.devViewConfigurations[environmentId] ?: project.files()
            }
        )
        val devNamespace = minecraft.devNamespace(environmentId)
        val sourceSteps = remap.pipelineSteps(environmentId, RemapSurface.SOURCES).get()

        // engines are selected through the same ServiceLoader registry as
        // genSources — the selection spec's input is the (lazy) first mod
        // jar, since selection only inspects spec traits
        val engineSpec = object : DecompileSpec {
            override val input: Provider<RegularFile> = project.layout.file(
                project.provider {
                    minecraft.modDevViewConfigurations.values
                        .flatMap { it.files }
                        .firstOrNull()
                }
            )
            override val classpath: FileCollection = libraries
            override val options: Map<String, String>
                get() = source.options.get()
        }
        val engine = DecompilerRegistry().select(engineSpec, source.engine.orNull)

        val sourcesViews = mutableListOf<Configuration>()
        for (bucket in MOD_BUCKETS) {
            val bucketName = bucket.get(environment)
            val modView = minecraft.modDevViewConfigurations["$environmentId:$bucketName"]
                ?: continue
            val remappedType = modView.attributes.getAttribute(
                ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE
            ) ?: error(
                "remapped mod view '${modView.name}' carries no artifact-type attribute"
            )
            val decompiledType = "unimined-modsrc-decompiled-$environmentId-$bucketName"
            val finalType = if (sourceSteps.isEmpty()) {
                decompiledType
            } else {
                "unimined-modsrc-$environmentId-$bucketName"
            }

            engine.registerModSourcesTransform(project, object : ModSourcesTransformSpec {
                override val fromArtifactType = remappedType
                override val toArtifactType = decompiledType
                override val classpath: FileCollection = libraries
                override val options: Map<String, String> = source.options.get()
            })

            if (sourceSteps.isNotEmpty()) {
                project.dependencies.registerTransform(RemapDependencyTransform::class.java) { t ->
                    t.from.attribute(
                        ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, decompiledType
                    )
                    t.to.attribute(
                        ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, finalType
                    )
                    t.parameters { p ->
                        p.label.set("$environmentId/$bucketName-sources")
                        p.modMarkers.set(project.provider {
                            ModMarkerScan.DEFAULT_MARKERS +
                                    remap.sourceSet(environmentId).modMarkerContributions
                        })
                        p.sourceNamespace.set(devNamespace)
                        p.targetNamespace.set(devNamespace)
                        p.mappingBundleJson.set(
                            minecraft.mappingBundleJson(environmentId, devNamespace, devNamespace)
                        )
                        // no classpath — see EnvironmentClasspathWiring;
                        // `libraries` remains the *decompiler* classpath
                        p.stepSpecs.set(remap.pipelineSteps(environmentId, RemapSurface.SOURCES))
                    }
                }
            }

            val bucketConfig = project.configurations.getByName(bucketName)
            sourcesViews += project.configurations.create(
                modSourcesViewName(bucketName)
            ) { conf ->
                conf.isCanBeConsumed = false
                conf.isCanBeResolved = true
                conf.extendsFrom(bucketConfig)
                conf.attributes.attribute(
                    ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, finalType
                )
            }
        }
        if (sourcesViews.isEmpty()) return

        val taskName = if (environmentId == "main") {
            "genModSources"
        } else {
            "gen${environmentId.cap()}ModSources"
        }
        val modSpecs = sourcesViews.map { view ->
            view.incoming.artifacts.resolvedArtifacts.map { results ->
                results.mapNotNull { result ->
                    val id = result.id.componentIdentifier
                    if (id !is ModuleComponentIdentifier || result.file.extension != "jar") null
                    else ModSourceSpec(id.group, id.module, id.version, result.file.name)
                }
            }
        }.fold(project.provider { emptyList<ModSourceSpec>() }) { acc, provider ->
            acc.zip(provider) { a, b -> a + b }
        }.map { specs ->
            specs.distinctBy { "${it.group}:${it.artifact}:${it.version}" }
        }

        project.tasks.register(taskName, GenModSourcesTask::class.java) { t ->
            t.group = "unimined"
            t.description =
                "Decompiles the '$environmentId' environment's mod dependencies into sources jars"
            t.sourceJars.from(sourcesViews)
            t.mods.set(modSpecs)
            t.localRepoDir.fileValue(minecraft.localRepoDir.toFile())
        }
    }

    /** `<bucket>ModSources` — non-main bucket names already carry the
     *  environment prefix (`fabricModImplementation`). */
    private fun modSourcesViewName(bucketName: String): String = "${bucketName}ModSources"

    private fun String.cap(): String = replaceFirstChar(Char::uppercase)

}
