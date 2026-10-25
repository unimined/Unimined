package xyz.wagyourtail.unimined.transform.fabric.internal

import org.gradle.api.Action
import org.gradle.api.Project
import org.gradle.api.artifacts.type.ArtifactTypeDefinition
import org.gradle.api.model.ObjectFactory
import xyz.wagyourtail.unimined.api.spec.RunConfigSpec
import xyz.wagyourtail.unimined.mapping.MappingsSpecImpl
import xyz.wagyourtail.unimined.minecraft.MinecraftEnvironment
import xyz.wagyourtail.unimined.remap.AccessRemapStepSpec
import xyz.wagyourtail.unimined.remap.RemapExtension
import xyz.wagyourtail.unimined.remap.spi.RemapSurface
import xyz.wagyourtail.unimined.transform.TransformExtension
import xyz.wagyourtail.unimined.transform.fabric.ApplyAccessWidenerStepSpec
import java.nio.file.Path

/**
 * Per-environment fabric wiring. Binding callbacks can run before the
 * build script assigns `loader(...)`, so every contribution checks the
 * loader spec lazily — inside dependency/dependency-materialization or
 * provider queries — rather than eagerly here.
 */
internal object FabricEnvironmentWiring {

    fun wire(project: Project, environment: MinecraftEnvironment) {
        contributeIntermediaryMappings(project, environment)
        contributeLoaderDependency(project, environment)
        contributeAccessSteps(project, environment)
        contributeRunMutator(project, environment)
    }

    /**
     * Prepend `official -> intermediary` to the environment's mapping
     * chain when a fabric loader is declared — user mappings (e.g. yarn)
     * then compose `intermediary -> named` on top, and a bare fabric
     * environment still lands on the `intermediary` dev namespace.
     */
    private fun contributeIntermediaryMappings(project: Project, environment: MinecraftEnvironment) {
        val files = project.files(project.provider {
            val loader = environment.loaderSpec.orNull
            if (loader?.name != "fabric") {
                emptyList()
            } else {
                listOf(IntermediaryMappings.materialize(project, environment.version.get()))
            }
        })
        environment.mappingsSpec.defaultSources += MappingsSpecImpl.Source.File(files)
    }

    /** The loader jar is an intermediary-named mod dependency — it rides
     *  the environment's remapped mod view onto the dev classpath. */
    private fun contributeLoaderDependency(project: Project, environment: MinecraftEnvironment) {
        project.configurations.named(environment.modImplementationConfigurationName) { conf ->
            conf.withDependencies { deps ->
                val loader = environment.loaderSpec.orNull
                if (loader?.name == "fabric") {
                    deps.add(project.dependencies.create(
                        "${FabricModAnalysis.LOADER_MODULE}:${loader.version}"
                    ))
                }
            }
        }
    }

    /**
     * Contribute the late JAR-surface steps: `apply-access-widener` merges
     * declared AWs and the ones extracted from direct mod dependencies
     * into the dev artifact, and `remap-access` rebases AW/AT/CT resources
     * embedded in mod jars onto the dev namespace.
     */
    private fun contributeAccessSteps(project: Project, environment: MinecraftEnvironment) {
        val objects: ObjectFactory = project.objects
        val remap = project.extensions.getByType(RemapExtension::class.java)
        val environmentSpec = remap.sourceSet(environment.environmentId)

        val modJars = project.configurations.create(
            "fabricModAnalysis${environment.environmentId.replaceFirstChar(Char::uppercase)}"
        ) { conf ->
            conf.isCanBeConsumed = false
            conf.isCanBeResolved = true
            conf.extendsFrom(
                project.configurations.getByName(environment.modImplementationConfigurationName),
                project.configurations.getByName(environment.modRuntimeOnlyConfigurationName)
            )
            conf.attributes.attribute(
                ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE,
                ArtifactTypeDefinition.JAR_TYPE
            )
        }
        val awCacheDir = project.layout.projectDirectory
            .dir(".gradle/unimined/fabric/aw").asFile.toPath()
        val extractedAws = project.provider {
            if (environment.loaderSpec.orNull?.name != "fabric") {
                emptyList()
            } else {
                modJars.files.mapNotNull {
                    FabricModAnalysis.extractAccessWidener(it.toPath(), awCacheDir)
                }
            }
        }

        val applySpec = objects.newInstance(ApplyAccessWidenerStepSpec::class.java)
        applySpec.accessWidenerFiles.from(environment.accessWideners, extractedAws)
        environmentSpec.contributeStep(RemapSurface.JAR, applySpec)
        environmentSpec.contributeStep(RemapSurface.JAR, AccessRemapStepSpec())
    }

    /**
     * Fabric launches through the loader's Knot entrypoint — read the
     * resolved loader jar's `mainClass` per side so run configs get the
     * right `KnotClient`/`KnotServer` (or a loader-declared override).
     */
    private fun contributeRunMutator(project: Project, environment: MinecraftEnvironment) {
        val transform = project.extensions.getByType(TransformExtension::class.java)
        val loaderJar = project.provider {
            val loader = environment.loaderSpec.orNull
            if (loader?.name != "fabric") {
                null
            } else {
                project.configurations.detachedConfiguration(
                    project.dependencies.create(
                        "${FabricModAnalysis.LOADER_MODULE}:${loader.version}"
                    )
                ).apply { isTransitive = false }.singleFile.toPath()
            }
        }
        transform.sourceSet(environment.environmentId).runMutators += Action<RunConfigSpec> { config ->
            val jar = loaderJar.orNull ?: return@Action
            val side = if (config.name.contains("server", true)) "server" else "client"
            val fallback = if (side == "server") {
                "net.fabricmc.loader.impl.launch.knot.KnotServer"
            } else {
                "net.fabricmc.loader.impl.launch.knot.KnotClient"
            }
            config.mainClass = FabricModAnalysis.loaderMainClass(jar, side) ?: fallback
        }
    }

}
