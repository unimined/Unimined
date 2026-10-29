package xyz.wagyourtail.unimined.transform.internal

import org.gradle.api.Project
import org.gradle.api.file.FileCollection
import xyz.wagyourtail.unimined.minecraft.MinecraftEnvironment
import xyz.wagyourtail.unimined.minecraft.MinecraftExtension
import xyz.wagyourtail.unimined.transform.JarProcessorsStepSpec
import xyz.wagyourtail.unimined.transform.JarProcessorsStepType
import xyz.wagyourtail.unimined.transform.TransformEnvironmentSpec
import xyz.wagyourtail.unimined.transform.processor.JarProcessor
import xyz.wagyourtail.unimined.transform.processor.JarProcessorsMappingsPostProcessor
import java.nio.file.Path

/**
 * Environment-scoped jar-processor wiring: the `jar-processors` step
 * spec for the dev-Minecraft transform, plus registration of the
 * processors' mapping halves on the environment's mapping spec.
 *
 * Both sides evaluate the environment's declared processor list lazily
 * (transform-wiring plugin order varies), and spec building resolves
 * the mod batch inside providers — nothing touches mod jars at
 * configuration time.
 */
internal object JarProcessorWiring {

    /**
     * The lazy `jar-processors` step spec for [environment]'s dev
     * artifact. Always created — the provider evaluates the effective
     * processor list at fingerprint/execution time and yields no specs
     * (a no-op step) when the environment declares none.
     */
    fun stepSpec(
        project: Project,
        minecraft: MinecraftExtension,
        environment: MinecraftEnvironment,
        envSpec: TransformEnvironmentSpec,
        modBatch: FileCollection
    ): JarProcessorsStepSpec =
        project.objects.newInstance(JarProcessorsStepSpec::class.java).apply {
            processorSpecs.set(project.provider {
                buildSpecs(
                    envSpec,
                    specContextProvider(minecraft, environment.environmentId, envSpec, modBatch)
                )
            })
            modJars.from(modBatch)
        }

    /**
     * Register the environment's [JarProcessor.processMappings] halves
     * as a spec-scoped post-processor on the environment's mapping
     * spec — the mod-provided-javadoc / interface-injection enrichment
     * channel.
     */
    fun wireMappings(
        minecraft: MinecraftExtension,
        environment: MinecraftEnvironment,
        envSpec: TransformEnvironmentSpec,
        modBatch: FileCollection
    ) {
        environment.mappingsSpec.postProcessors += JarProcessorsMappingsPostProcessor(
            { envSpec.effectiveJarProcessors() },
            specContextProvider(minecraft, environment.environmentId, envSpec, modBatch)
        )
    }

    /**
     * `() -> SpecContext` shared by the step spec and the mapping
     * post-processor: production namespace + the resolved mod batch,
     * routed through the loader's spec-context factory when installed
     * (fabric supplies fmj-aware mod infos).
     */
    private fun specContextProvider(
        minecraft: MinecraftExtension,
        environmentId: String,
        envSpec: TransformEnvironmentSpec,
        modBatch: FileCollection
    ): (String?) -> JarProcessor.SpecContext = { nsOverride ->
        // callers processing mapping data may supply the production
        // namespace from the in-flight tree — resolving it via
        // modSourceNamespaces would re-enter the env's own mapping
        // resolution (recursive update)
        val productionNamespace = nsOverride ?: minecraft
            .modSourceNamespaces(environmentId)
            .map { it.singleOrNull() ?: it.first() }.get()
        val modPaths = modBatch.files.map { it.toPath() }
        envSpec.effectiveJarProcessorSpecContext()
            ?.invoke(productionNamespace, modPaths)
            ?: BasicSpecContext(productionNamespace, modPaths)
    }

    /**
     * Build every declared processor's spec over a freshly created
     * [JarProcessor.SpecContext] and serialize each as `name payload`
     * — the step's fingerprinted input.
     */
    private fun buildSpecs(
        envSpec: TransformEnvironmentSpec,
        specContext: (String?) -> JarProcessor.SpecContext
    ): List<String> {
        val processors = envSpec.effectiveJarProcessors()
        if (processors.isEmpty()) return emptyList()
        val ctx = specContext(null)
        return processors.mapNotNull { processor ->
            processor.buildSpec(ctx)?.let { spec ->
                "${processor.name} ${processor.serializeSpec(spec)}"
            }
        }
    }

}

/** File-only [JarProcessor.SpecContext] — the non-loader-aware default. */
internal class BasicSpecContext(
    override val productionNamespace: String,
    private val modPaths: List<Path>
) : JarProcessor.SpecContext {

    override fun mods(): List<JarProcessor.ModJarInfo> =
        modPaths.map { JarProcessorsStepType.BasicModJarInfo(it) }

}
