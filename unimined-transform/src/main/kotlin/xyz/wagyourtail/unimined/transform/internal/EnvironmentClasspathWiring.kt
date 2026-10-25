package xyz.wagyourtail.unimined.transform.internal

import org.gradle.api.Action
import org.gradle.api.Project
import org.gradle.api.artifacts.type.ArtifactTypeDefinition
import org.gradle.api.tasks.SourceSet
import xyz.wagyourtail.unimined.minecraft.MinecraftEnvironment
import xyz.wagyourtail.unimined.minecraft.MinecraftExtension
import xyz.wagyourtail.unimined.remap.RemapExtension
import xyz.wagyourtail.unimined.remap.spi.RemapSurface
import xyz.wagyourtail.unimined.remap.transform.RemapDependencyTransform

/**
 * Wires one bound environment's dev-namespace artifacts onto its source
 * set's classpaths.
 *
 * - `<env>MinecraftDev` — resolvable view over the minecraft stub bucket
 *   requesting `unimined-mc-dev-<env>`; Gradle chains the registered
 *   `jar → raw` and `raw → dev` transforms, so the dev artifact
 *   materializes at resolution time (also during IDE sync).
 * - `raw → dev` is the environment's JAR remap pipeline
 *   ([RemapDependencyTransform]); namespaces arrive as lazy providers so
 *   nothing resolves mappings at configuration time.
 * - `modImplementation`/`modRuntimeOnly` buckets get remapped views via
 *   [RemapExtension.remappedViewFor] and are appended to the source set's
 *   classpaths — keeping `Gradle: g:a:v` library identity in IDEs.
 */
internal object EnvironmentClasspathWiring {

    fun wire(
        project: Project,
        environment: MinecraftEnvironment,
        sourceSet: SourceSet
    ) {
        val minecraft = project.extensions.getByType(MinecraftExtension::class.java)
        val remap = project.extensions.getByType(RemapExtension::class.java)
        val environmentId = environment.environmentId

        val minecraftBucket = project.configurations.getByName(
            environment.minecraftConfigurationName
        )
        val libraries = project.configurations.getByName(
            environment.minecraftLibrariesConfigurationName
        )

        val devView = project.configurations.create(environment.minecraftDevViewName) { conf ->
            conf.isCanBeConsumed = false
            conf.isCanBeResolved = true
            conf.extendsFrom(minecraftBucket)
            conf.attributes.attribute(
                ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE,
                minecraft.devArtifactType(environmentId)
            )
        }
        minecraft.devViewConfigurations[environmentId] = devView

        val devNamespace = minecraft.devNamespace(environmentId)
        val mcSnapshot = minecraft.mappingSnapshot(
            environmentId, "mcToDev",
            minecraft.sourceNamespace(environmentId), devNamespace
        )
        val modsSnapshot = minecraft.mappingSnapshot(
            environmentId, "modsToDev",
            minecraft.modSourceNamespaces(environmentId).map { it.single() },
            devNamespace
        )

        project.dependencies.registerTransform(RemapDependencyTransform::class.java) { t ->
            t.from.attribute(
                ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE,
                minecraft.rawArtifactType(environmentId)
            )
            t.to.attribute(
                ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE,
                minecraft.devArtifactType(environmentId)
            )
            t.parameters { p ->
                p.sourceNamespace.set(minecraft.sourceNamespace(environmentId))
                p.targetNamespace.set(devNamespace)
                p.mappingFile.set(mcSnapshot)
                p.classpath.from(libraries)
                p.stepSpecs.set(remap.pipelineSteps(environmentId, RemapSurface.JAR))
            }
        }

        val modImplementationView = remappedModView(
            project, minecraft, remap, environment,
            environment.modImplementationConfigurationName, modsSnapshot
        )
        val modRuntimeOnlyView = remappedModView(
            project, minecraft, remap, environment,
            environment.modRuntimeOnlyConfigurationName, modsSnapshot
        )

        sourceSet.compileClasspath = sourceSet.compileClasspath +
            project.files(modImplementationView)
        sourceSet.runtimeClasspath = sourceSet.runtimeClasspath +
            project.files(modImplementationView, modRuntimeOnlyView)
    }

    private fun remappedModView(
        project: Project,
        minecraft: MinecraftExtension,
        remap: RemapExtension,
        environment: MinecraftEnvironment,
        bucketName: String,
        modsSnapshot: org.gradle.api.provider.Provider<org.gradle.api.file.RegularFile>
    ): org.gradle.api.artifacts.Configuration {
        val environmentId = environment.environmentId
        return remap.remappedViewFor(environmentId, bucketName, Action { spec ->
            spec.artifactTypeToken.set("unimined-remapped-$environmentId-$bucketName")
            spec.stepSpecs.set(remap.pipelineSteps(environmentId, RemapSurface.JAR))
            spec.sourceNamespace.set(
                minecraft.modSourceNamespaces(environmentId).map { namespaces ->
                    require(namespaces.size == 1) {
                        "environment '$environmentId' declares ${namespaces.size} mod " +
                            "source namespaces for bucket '$bucketName' — auto wiring needs " +
                            "exactly one; declare remap.remappedViews(...) for mixed inputs"
                    }
                    namespaces.single()
                }
            )
            spec.targetNamespace.set(minecraft.devNamespace(environmentId))
            spec.mappingFile.set(modsSnapshot)
            spec.classpath.from(
                project.configurations.getByName(environment.minecraftLibrariesConfigurationName)
            )
        })
    }

}
