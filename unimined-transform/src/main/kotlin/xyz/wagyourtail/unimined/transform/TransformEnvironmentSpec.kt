package xyz.wagyourtail.unimined.transform

import org.gradle.api.Action
import org.gradle.api.GradleException
import org.gradle.api.model.ObjectFactory
import xyz.wagyourtail.unimined.transform.step.StepTypeRegistry
import xyz.wagyourtail.unimined.transform.feature.FeatureRegistry
import xyz.wagyourtail.unimined.api.environment.EnvironmentReference
import xyz.wagyourtail.unimined.api.spec.RunConfigSpec
import xyz.wagyourtail.unimined.transform.processor.JarProcessor
import xyz.wagyourtail.unimined.transform.spi.MinecraftTransformer
import xyz.wagyourtail.unimined.transform.spi.TransformDecorator
import java.nio.file.Path

/**
 * The ordered transformer and decorator registrations for one Minecraft
 * source-set environment.
 */
class TransformEnvironmentSpec internal constructor(
    /** Stable source-set/environment id. */
    val environmentId: String,
    private val projectPath: String,
    objects: ObjectFactory
) {

    /** Declared step pipeline — `pipeline { binpatches { } … }`.
     *  Empty for environments driven by [transformers] (the monolith
     *  path still resolves while modules port to declared steps). */
    val pipeline = objects.newInstance(PipelineSpec::class.java, StepTypeRegistry(), objects)

    /** `pipeline { }` DSL verb. */
    fun pipeline(action: Action<in PipelineSpec>) {
        action.execute(pipeline)
    }

    /** Declared capability bindings — `features { nestedJars("jarjar") }`. */
    val features = FeaturesSpec(FeatureRegistry())

    /** `features { }` DSL verb. */
    fun features(action: Action<in FeaturesSpec>) {
        action.execute(features)
    }

    /** Declared transformers in execution order. */
    val transformers: MutableList<MinecraftTransformer> = mutableListOf()

    /** Cross-cutting decorators in application order. */
    val decorators: MutableList<TransformDecorator> = mutableListOf()

    /**
     * Run-config adjustments applied after all declared transformers —
     * contributed by loader plugins that wire run behavior without a full
     * transform task (e.g. fabric swapping the launch main class).
     */
    val runMutators: MutableList<Action<RunConfigSpec>> = mutableListOf()

    /**
     * Mutators applied to the registered run *task* after spec-level
     * [runMutators] — for actions needing task state (e.g. runDirectory
     * for copying files into the run dir), which the spec cannot see.
     */
    val runTaskMutators: MutableList<Action<org.gradle.api.Task>> = mutableListOf()

    /**
     * Mutators applied to the `prepare<run>` task — staging that must
     * materialize *before* the run task executes (run-dir contents,
     * mod staging, dyn-lib copies). The prepare task carries the same
     * `runDirectory` property, so mutators shape it identically.
     */
    val prepareTaskMutators: MutableList<Action<org.gradle.api.Task>> = mutableListOf()

    /**
     * [JarProcessor][xyz.wagyourtail.unimined.transform.processor.JarProcessor]
     * declarations in execution order — their specs run over the
     * dev-Minecraft artifact via the `jar-processors` pipeline step, and
     * any `processMappings` halves through the environment's mapping
     * enrichment.
     */
    val jarProcessors: MutableList<JarProcessor> = mutableListOf()

    /**
     * Loader-supplied spec-context factory — fabric installs an
     * fmj-aware [JarProcessor.ModJarInfo] so processors can read
     * `custom` metadata keys. `null` = file-only mod infos.
     */
    var jarProcessorSpecContext: ((String, List<Path>) -> JarProcessor.SpecContext)? = null

    private val inheritedEnvironments =
        linkedMapOf<EnvironmentReference, () -> TransformEnvironmentSpec>()

    /** Add a transformer to this environment's pipeline. */
    fun transformer(transformer: MinecraftTransformer) {
        transformers.add(transformer)
    }

    /** Add a decorator to this environment's Minecraft artifact. */
    fun decorator(decorator: TransformDecorator) {
        decorators.add(decorator)
    }

    internal fun inheritFrom(
        source: EnvironmentReference,
        environment: () -> TransformEnvironmentSpec
    ) {
        inheritedEnvironments[source] = environment
    }

    /**
     * The environments this one inherits transforms from — the 2.0
     * `combineWith` analog. Exposed for run mutators that need
     * group attribution (e.g. forge `MOD_CLASSES`).
     */
    fun inheritedEnvironmentReferences(): Set<EnvironmentReference> =
        inheritedEnvironments.keys.toSet()

    /**
     * Transformers are inherited as a section, not appended to an explicitly
     * configured target pipeline. This lets `copyFrom(...)` supply a neutral
     * common pipeline while a later loader transform still replaces it.
     */
    internal fun effectiveTransformers(
        visiting: MutableSet<String> = mutableSetOf()
    ): List<MinecraftTransformer> {
        if (transformers.isNotEmpty()) return transformers
        val key = "$projectPath:$environmentId"
        if (!visiting.add(key)) {
            throw GradleException("cyclic Unimined transform inheritance involving $key")
        }
        try {
            for (resolver in inheritedEnvironments.values.reversed()) {
                val inherited = resolver().effectiveTransformers(visiting)
                if (inherited.isNotEmpty()) return inherited
            }
            return emptyList()
        } finally {
            visiting.remove(key)
        }
    }

    /** Decorators compose: inherited decorators run before local decorators. */
    internal fun effectiveDecorators(
        visiting: MutableSet<String> = mutableSetOf()
    ): List<TransformDecorator> {
        val key = "$projectPath:$environmentId"
        if (!visiting.add(key)) {
            throw GradleException("cyclic Unimined transform inheritance involving $key")
        }
        try {
            return inheritedEnvironments.values.flatMap { resolver ->
                resolver().effectiveDecorators(visiting)
            } + decorators
        } finally {
            visiting.remove(key)
        }
    }

    /** Run mutators compose like decorators: inherited before local. */
    fun effectiveRunMutators(
        visiting: MutableSet<String> = mutableSetOf()
    ): List<Action<RunConfigSpec>> {
        val key = "$projectPath:$environmentId"
        if (!visiting.add(key)) {
            throw GradleException("cyclic Unimined transform inheritance involving $key")
        }
        try {
            return inheritedEnvironments.values.flatMap { resolver ->
                resolver().effectiveRunMutators(visiting)
            } + runMutators
        } finally {
            visiting.remove(key)
        }
    }

    /** Run task mutators compose like [effectiveRunMutators]. */
    fun effectiveRunTaskMutators(
        visiting: MutableSet<String> = mutableSetOf()
    ): List<Action<org.gradle.api.Task>> {
        val key = "$projectPath:$environmentId"
        if (!visiting.add(key)) {
            throw GradleException("cyclic Unimined transform inheritance involving $key")
        }
        try {
            return inheritedEnvironments.values.flatMap { resolver ->
                resolver().effectiveRunTaskMutators(visiting)
            } + runTaskMutators
        } finally {
            visiting.remove(key)
        }
    }

    /** Prepare task mutators compose like [effectiveRunTaskMutators]. */
    fun effectivePrepareTaskMutators(
        visiting: MutableSet<String> = mutableSetOf()
    ): List<Action<org.gradle.api.Task>> {
        val key = "$projectPath:$environmentId"
        if (!visiting.add(key)) {
            throw GradleException("cyclic Unimined transform inheritance involving $key")
        }
        try {
            return inheritedEnvironments.values.flatMap { resolver ->
                resolver().effectivePrepareTaskMutators(visiting)
            } + prepareTaskMutators
        } finally {
            visiting.remove(key)
        }
    }

    /** Jar processors compose like decorators: inherited before local. */
    internal fun effectiveJarProcessors(
        visiting: MutableSet<String> = mutableSetOf()
    ): List<JarProcessor> {
        val key = "$projectPath:$environmentId"
        if (!visiting.add(key)) {
            throw GradleException("cyclic Unimined transform inheritance involving $key")
        }
        try {
            return inheritedEnvironments.values.flatMap { resolver ->
                resolver().effectiveJarProcessors(visiting)
            } + jarProcessors
        } finally {
            visiting.remove(key)
        }
    }

    /**
     * The effective spec-context factory — nearest declaration wins,
     * since the loader wiring that installs it also installs the
     * processors consuming its metadata.
     */
    internal fun effectiveJarProcessorSpecContext(
        visiting: MutableSet<String> = mutableSetOf()
    ): ((String, List<Path>) -> JarProcessor.SpecContext)? {
        jarProcessorSpecContext?.let { return it }
        val key = "$projectPath:$environmentId"
        if (!visiting.add(key)) {
            throw GradleException("cyclic Unimined transform inheritance involving $key")
        }
        try {
            for (resolver in inheritedEnvironments.values.reversed()) {
                resolver().effectiveJarProcessorSpecContext(visiting)?.let { return it }
            }
            return null
        } finally {
            visiting.remove(key)
        }
    }

}
