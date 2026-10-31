package xyz.wagyourtail.unimined.transform

import groovy.lang.Closure
import groovy.lang.DelegatesTo
import org.gradle.api.Action
import org.gradle.api.Project
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import xyz.wagyourtail.unimined.api.environment.EnvironmentCopyRegistry
import xyz.wagyourtail.unimined.api.spec.MappingsSpec
import xyz.wagyourtail.unimined.api.spec.RunConfigSpec
import xyz.wagyourtail.unimined.minecraft.spi.MinecraftArtifacts
import xyz.wagyourtail.unimined.transform.internal.DefaultTransformContext
import xyz.wagyourtail.unimined.transform.internal.RuntimeTransformOutput
import xyz.wagyourtail.unimined.transform.spi.MinecraftTransformer
import xyz.wagyourtail.unimined.transform.spi.TransformDecorator
import javax.inject.Inject

/**
 * The `transform { }` DSL + pipeline host.
 *
 * Transforms are *declared, not applied* — `unimined-transform-*` plugins
 * (or `buildSrc`/inline transforms built on the same builder API) register
 * themselves via [transformer]; cross-cutting decorators via [decorator].
 * [build] wires the declared pipeline `provider → transformer(s) → dev
 * artifact` entirely through `Provider`/`TaskProvider` chains.
 */
abstract class TransformExtension @Inject constructor(
    private val project: Project
) {

    private val environments = mutableMapOf(
        "main" to TransformEnvironmentSpec("main", project.path, project.objects)
    )

    init {
        EnvironmentCopyRegistry.registerHandler(
            project,
            TransformEnvironmentCopyHandler(project)
        )
    }

    /** The default `main` environment's transformers. */
    val transformers: MutableList<MinecraftTransformer>
        get() = sourceSet("main").transformers

    /** The default `main` environment's decorators. */
    val decorators: MutableList<TransformDecorator>
        get() = sourceSet("main").decorators

    /** Get or create this source-set environment's transform configuration. */
    fun sourceSet(environmentId: String): TransformEnvironmentSpec =
        environments.getOrPut(environmentId) {
            TransformEnvironmentSpec(environmentId, project.path, project.objects)
        }

    /** Configure a source-set environment's transform pipeline. */
    fun sourceSet(
        environmentId: String,
        action: Action<TransformEnvironmentSpec>
    ): TransformEnvironmentSpec = sourceSet(environmentId).also(action::execute)

    /** Groovy DSL overload for source-set transform configuration. */
    fun sourceSet(
        environmentId: String,
        @DelegatesTo(value = TransformEnvironmentSpec::class, strategy = Closure.DELEGATE_FIRST)
        action: Closure<*>
    ): TransformEnvironmentSpec = sourceSet(environmentId).also { environment ->
        action.delegate = environment
        action.resolveStrategy = Closure.DELEGATE_FIRST
        action.call()
    }

    /** Register a transform on `main` (compatibility convenience). */
    fun transformer(t: MinecraftTransformer) {
        sourceSet("main").transformer(t)
    }

    /** Register a transform on [environmentId]. */
    fun transformer(environmentId: String, t: MinecraftTransformer) {
        sourceSet(environmentId).transformer(t)
    }

    /** Register a cross-cutting decorator on `main`. */
    fun decorator(d: TransformDecorator) {
        sourceSet("main").decorator(d)
    }

    /** Register a cross-cutting decorator on [environmentId]. */
    fun decorator(environmentId: String, d: TransformDecorator) {
        sourceSet(environmentId).decorator(d)
    }

    /**
     * Build the pipeline for an environment: wires each transformer's task
     * input to the previous stage's (decorated) output, feeds each
     * transform the prior stage's emitted outputs, and chains decorators
     * on top of every transformer's main artifact.
     *
     * @param artifacts the negotiated provider output
     * @param mappings the environment's mapping declaration
     * @param input the artifact the pipeline transforms — typically the
     *  provider's side-appropriate jar
     */
    fun build(
        artifacts: MinecraftArtifacts,
        mappings: MappingsSpec,
        input: Provider<RegularFile>
    ): TransformPipeline = build("main", artifacts, mappings, input)

    /** Build a pipeline using the transforms declared for [environmentId]. */
    fun build(
        environmentId: String,
        artifacts: MinecraftArtifacts,
        mappings: MappingsSpec,
        input: Provider<RegularFile>
    ): TransformPipeline {
        val environment = sourceSet(environmentId)
        val transformers = environment.effectiveTransformers()
        val decorators = environment.effectiveDecorators()
        var current = input
        val inputsSoFar = mutableListOf<RuntimeTransformOutput>()
        val outputs = linkedMapOf<String, Provider<RegularFile>>()

        for (t in transformers) {
            val ctx = DefaultTransformContext(
                environmentId, artifacts, mappings, inputsSoFar.toList(),
                decorators
            )
            t.configure(ctx)

            ctx.emitted.forEach { (name, artifact) ->
                outputs[name] = artifact
                inputsSoFar += RuntimeTransformOutput(name, artifact)
            }

            val prev = current
            val task = t.transformTaskProvider
            task.configure { it.inputArtifact.set(prev) }
            var out: Provider<RegularFile> = task.flatMap { it.outputArtifact }
            for (decorator in decorators) {
                out = decorator.apply(out, ctx)
            }
            outputs["jar"] = out
            inputsSoFar += RuntimeTransformOutput("jar", out)
            current = out
        }

        return TransformPipeline(outputs.toMap(), current)
    }

    /**
     * Run-config projection hook — each declared transform mutates the
     * config in pipeline order (forge injects tweakClass args, fabric
     * swaps the main class).
     */
    fun transformRun(config: RunConfigSpec) {
        transformRun("main", config)
    }

    /** Apply run transformations for [environmentId] in declaration order. */
    fun transformRun(environmentId: String, config: RunConfigSpec) {
        val environment = sourceSet(environmentId)
        environment.effectiveTransformers().forEach { it.transformRun(config) }
        environment.effectiveRunMutators().forEach { it.execute(config) }
    }

}
