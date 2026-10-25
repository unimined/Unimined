package xyz.wagyourtail.unimined.transform

import org.gradle.api.Action
import org.gradle.api.GradleException
import xyz.wagyourtail.unimined.api.environment.EnvironmentReference
import xyz.wagyourtail.unimined.api.spec.RunConfigSpec
import xyz.wagyourtail.unimined.transform.spi.MinecraftTransformer
import xyz.wagyourtail.unimined.transform.spi.TransformDecorator

/**
 * The ordered transformer and decorator registrations for one Minecraft
 * source-set environment.
 */
class TransformEnvironmentSpec internal constructor(
    /** Stable source-set/environment id. */
    val environmentId: String,
    private val projectPath: String
) {

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

}
