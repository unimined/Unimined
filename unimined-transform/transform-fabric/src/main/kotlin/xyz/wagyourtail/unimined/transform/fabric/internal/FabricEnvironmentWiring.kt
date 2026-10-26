package xyz.wagyourtail.unimined.transform.fabric.internal

import org.gradle.api.Action
import org.gradle.api.Project
import org.gradle.api.artifacts.type.ArtifactTypeDefinition
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import xyz.wagyourtail.unimined.api.spec.LoaderSpec
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
 * loader spec lazily — inside dependency materialization or provider
 * queries — and providers capture only spec properties, file
 * collections, and paths so they survive the configuration cache.
 */
internal object FabricEnvironmentWiring {

    fun wire(project: Project, environment: MinecraftEnvironment) {
        // fabric's MC artifact is the vanilla jar — the transform layers
        // loader behavior on top; convention lets users override, and the
        // convention itself only applies when the loader is fabric
        environment.provider.convention(
            environment.loaderSpec.map { if (it.name == "fabric") "native" else null }
        )
        contributeIntermediaryMappings(project, environment)
        contributeLoaderDependency(project, environment)
        contributeAccessSteps(project, environment)
        contributeRunMutator(project, environment)
    }

    private fun String.cap(): String = replaceFirstChar(Char::uppercase)

    private const val INTERMEDIARY_MODULE = "net.fabricmc:intermediary"

    /**
     * Prepend `official -> intermediary` to the environment's mapping
     * chain when a fabric loader is declared — user mappings (e.g. yarn)
     * then compose `intermediary -> named` on top, and a bare fabric
     * environment still lands on the `intermediary` dev namespace.
     * The raw `intermediary` jar is contributed as the source; the
     * mapping materializer unwraps its `mappings/mappings.tiny`.
     */
    private fun contributeIntermediaryMappings(project: Project, environment: MinecraftEnvironment) {
        val loaderSpec: Property<LoaderSpec> = environment.loaderSpec
        val version = environment.version
        val dependencies = project.dependencies

        val intermediary = project.configurations.create(
            "fabricIntermediary${environment.environmentId.cap()}"
        ) { conf ->
            conf.isCanBeConsumed = false
            conf.isCanBeResolved = true
            conf.withDependencies { deps ->
                if (loaderSpec.orNull?.name == "fabric") {
                    deps.add(dependencies.create(
                        "$INTERMEDIARY_MODULE:${version.get()}:v2"
                    ))
                }
            }
        }
        val intermediaryJar = project.files(intermediary)

        val files = project.files(project.provider {
            if (loaderSpec.orNull?.name != "fabric") {
                emptyList()
            } else {
                listOf(intermediaryJar.singleFile)
            }
        })
        environment.mappingsSpec.defaultSources += MappingsSpecImpl.Source.File(files)
    }

    /** The loader jar is an intermediary-named mod dependency — it rides
     *  the environment's remapped mod view onto the dev classpath. */
    private fun contributeLoaderDependency(project: Project, environment: MinecraftEnvironment) {
        val loaderSpec: Property<LoaderSpec> = environment.loaderSpec
        val dependencies = project.dependencies

        val loader = project.configurations.create(
            "fabricLoader${environment.environmentId.cap()}"
        ) { conf ->
            conf.isCanBeConsumed = false
            conf.isCanBeResolved = true
            conf.isTransitive = false
            conf.withDependencies { deps ->
                val spec = loaderSpec.orNull
                if (spec?.name == "fabric") {
                    deps.add(dependencies.create(
                        "${FabricModAnalysis.LOADER_MODULE}:${spec.version}"
                    ))
                }
            }
        }
        // loader's own runtime libraries — the pom declares none; the jar
        // embeds fabric-installer.json listing them (asm, sponge-mixin)
        val loaderFiles = project.files(loader)
        val loaderLibraries = project.configurations.create(
            "fabricLoaderLibraries${environment.environmentId.cap()}"
        ) { conf ->
            conf.isCanBeConsumed = false
            conf.isCanBeResolved = true
            conf.isTransitive = false
            conf.withDependencies { deps ->
                val spec = loaderSpec.orNull
                if (spec?.name != "fabric") return@withDependencies
                val jar = loaderFiles.singleFile
                FabricModAnalysis.loaderLibraries(jar.toPath()).forEach {
                    deps.add(dependencies.create(it))
                }
            }
        }
        project.configurations.named(environment.modImplementationConfigurationName) { conf ->
            conf.extendsFrom(loader, loaderLibraries)
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
        val loaderSpec: Property<LoaderSpec> = environment.loaderSpec
        val remap = project.extensions.getByType(RemapExtension::class.java)
        val environmentSpec = remap.sourceSet(environment.environmentId)

        val modJars = project.configurations.create(
            "fabricModAnalysis${environment.environmentId.cap()}"
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
        val modJarFiles = project.files(modJars)
        val awCacheDir = project.layout.projectDirectory
            .dir(".gradle/unimined/fabric/aw").asFile.toPath()
        val extractedAws = project.provider {
            if (loaderSpec.orNull?.name != "fabric") {
                emptyList()
            } else {
                modJarFiles.files.mapNotNull {
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
        val loaderSpec: Property<LoaderSpec> = environment.loaderSpec
        val transform = project.extensions.getByType(TransformExtension::class.java)

        val loader = project.configurations.getByName(
            "fabricLoader${environment.environmentId.cap()}"
        )
        val loaderFiles = project.files(loader)

        transform.sourceSet(environment.environmentId).runMutators += Action<RunConfigSpec> { config ->
            if (loaderSpec.orNull?.name != "fabric") return@Action
            val jar = loaderFiles.singleFile.toPath()
            val side = if (config.name.contains("server", ignoreCase = true)) "server" else "client"
            val fallback = if (side == "server") {
                "net.fabricmc.loader.impl.launch.knot.KnotServer"
            } else {
                "net.fabricmc.loader.impl.launch.knot.KnotClient"
            }
            config.mainClass = FabricModAnalysis.loaderMainClass(jar, side) ?: fallback
        }
    }

}
