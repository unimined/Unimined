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
        // Bundle JSON providers — evaluated at transform execution
        // (dependency resolution), since artifact transforms cannot carry
        // task dependencies on a bundle producer.
        val mcBundleJson = minecraft.mappingBundleJson(
            environmentId,
            minecraft.sourceNamespace(environmentId), devNamespace
        )
        val modsBundleJson = minecraft.mappingBundleJson(
            environmentId,
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
                p.mappingBundleJson.set(mcBundleJson)
                p.classpath.from(libraries)
                p.stepSpecs.set(remap.pipelineSteps(environmentId, RemapSurface.JAR))
            }
        }

        val modImplementationView = remappedModView(
            project, minecraft, remap, environment,
            environment.modImplementationConfigurationName, modsBundleJson
        )
        val modRuntimeOnlyView = remappedModView(
            project, minecraft, remap, environment,
            environment.modRuntimeOnlyConfigurationName, modsBundleJson
        )
        minecraft.modDevViewConfigurations[
            "$environmentId:${environment.modImplementationConfigurationName}"
        ] = modImplementationView
        minecraft.modDevViewConfigurations[
            "$environmentId:${environment.modRuntimeOnlyConfigurationName}"
        ] = modRuntimeOnlyView

        sourceSet.compileClasspath = sourceSet.compileClasspath +
            project.files(modImplementationView)
        sourceSet.runtimeClasspath = sourceSet.runtimeClasspath +
            project.files(modImplementationView, modRuntimeOnlyView)

        ProductionJarWiring.wire(project, environment, sourceSet)
    }

    private fun remappedModView(
        project: Project,
        minecraft: MinecraftExtension,
        remap: RemapExtension,
        environment: MinecraftEnvironment,
        bucketName: String,
        modsBundleJson: org.gradle.api.provider.Provider<String>
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
            spec.mappingBundleJson.set(modsBundleJson)
            spec.hasLazyMappings = true
            spec.classpath.from(
                project.configurations.getByName(environment.minecraftLibrariesConfigurationName)
            )
        })
    }

}
