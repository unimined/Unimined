package xyz.wagyourtail.unimined.minecraft

import org.gradle.api.GradleException
import xyz.wagyourtail.unimined.api.environment.EnvironmentCopyHandler
import xyz.wagyourtail.unimined.api.environment.EnvironmentCopyRequest
import xyz.wagyourtail.unimined.api.environment.EnvironmentSection

/** Copies Minecraft-owned sections between environments. */
internal class MinecraftEnvironmentCopyHandler(
    private val extension: MinecraftExtension
) : EnvironmentCopyHandler {

    override val sections: Set<EnvironmentSection> = setOf(
        EnvironmentSection.VERSION,
        EnvironmentSection.SIDE,
        EnvironmentSection.PROVIDER,
        EnvironmentSection.LOADER,
        EnvironmentSection.MAPPINGS,
        EnvironmentSection.DEPENDENCIES
    )

    override fun registerCopy(request: EnvironmentCopyRequest) {
        val requested = request.sections.intersect(sections)
        if (requested.isEmpty()) return
        val target = extension.sourceSet(request.target.environmentId)

        if (EnvironmentSection.VERSION in requested) {
            val sourceVersion = extension.project.provider {
                source(request).version.orNull
            }
            target.version.convention(sourceVersion)
            // inherit-from must not silently span mc versions: when the
            // target pinned its own version it has to agree with the
            // source's — checked once this project is configured
        }
        if (EnvironmentSection.SIDE in requested) {
            target.side.convention(extension.project.provider {
                source(request).side.orNull
            })
        }
        if (EnvironmentSection.PROVIDER in requested) {
            target.provider.convention(extension.project.provider {
                source(request).provider.orNull
            })
        }
        if (EnvironmentSection.LOADER in requested) {
            target.loaderSpec.convention(extension.project.provider {
                source(request).loaderSpec.orNull
            })
        }
        if (EnvironmentSection.MAPPINGS in requested) {
            target.mappingsSpec.inheritSources { visiting ->
                source(request).mappingsSpec.effectiveSources(visiting)
            }
        }
        if (EnvironmentSection.DEPENDENCIES in requested) {
            wireDependencies(request, target)
        }
    }

    private fun wireDependencies(
        request: EnvironmentCopyRequest,
        target: MinecraftEnvironment
    ) {
        if (request.source.projectPath != request.target.projectPath) {
            throw GradleException(
                "Cannot copy dependency buckets across projects for " +
                        "'${request.target.projectPath}:${request.target.environmentId}' " +
                        "from '${request.source.projectPath}:${request.source.environmentId}'; " +
                        "combine source output or declare a project dependency instead"
            )
        }
        val source = extension.environment(request.source)
        extension.ensureModConfigurations(source)
        val sourceImplementation = extension.project.configurations.getByName(
            source.modImplementationConfigurationName
        )
        val sourceRuntime = extension.project.configurations.getByName(
            source.modRuntimeOnlyConfigurationName
        )

        extension.project.configurations.matching {
            it.name == target.modImplementationConfigurationName
        }.all { targetConfiguration ->
            if (sourceImplementation !in targetConfiguration.extendsFrom) {
                targetConfiguration.extendsFrom(sourceImplementation)
            }
        }
        extension.project.configurations.matching {
            it.name == target.modRuntimeOnlyConfigurationName
        }.all { targetConfiguration ->
            if (sourceRuntime !in targetConfiguration.extendsFrom) {
                targetConfiguration.extendsFrom(sourceRuntime)
            }
        }

        // plain sourceSet buckets alongside the mod buckets —
        // DEPENDENCIES means the env's whole dependency surface, not
        // only mod-scoped configurations; aux envs (gametest/datagen)
        // rely on this for parent library deps like project(":core").
        // Java-plugin naming: `main` uses the global buckets, other
        // envs use `<envId><Suffix>`; matching().all stays lazy-safe
        // since the source env's source set may not be bound yet.
        for (suffix in listOf("Api", "Implementation", "CompileOnly", "RuntimeOnly")) {
            val sourceName = dependencyBucketName(source.environmentId, suffix)
            val targetName = dependencyBucketName(target.environmentId, suffix)
            extension.project.configurations.matching { it.name == targetName }
                .all { targetConfiguration ->
                    extension.project.configurations.matching { it.name == sourceName }
                        .all { sourceConfiguration ->
                            if (sourceConfiguration !in targetConfiguration.extendsFrom) {
                                targetConfiguration.extendsFrom(sourceConfiguration)
                            }
                        }
                }
        }
    }

    private fun dependencyBucketName(environmentId: String, suffix: String): String =
        if (environmentId == "main") suffix.replaceFirstChar(Char::lowercaseChar)
        else "$environmentId$suffix"

    private fun source(request: EnvironmentCopyRequest): MinecraftEnvironment =
        extension.environment(request.source)

}
