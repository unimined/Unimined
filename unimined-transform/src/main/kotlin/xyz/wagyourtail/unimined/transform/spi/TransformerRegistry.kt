package xyz.wagyourtail.unimined.transform.spi

import java.util.ServiceLoader
import org.gradle.api.Project
import xyz.wagyourtail.unimined.transform.TransformEnvironmentSpec
import xyz.wagyourtail.unimined.transform.loader.LoaderDescriptor
import xyz.wagyourtail.unimined.minecraft.MinecraftEnvironment

/**
 * Selection layer over [MinecraftTransformer] impls — each declares
 * the loader families it claims; an environment resolves its
 * transform by the descriptor row's `family`.
 *
 * [claimedFamilies] is how "lowest selector precedence" works:
 * `custom` declares [claimedFamilies] empty plus
 * [fallbackClaim] = true — it wins only when no registered transform
 * claims the env's family (rowless envs and unclaimed families).
 */
interface TransformerClaim {

    /** Loader families this transform claims (`fabric`/`forge`/…). */
    val claimedFamilies: Set<String>
        get() = emptySet()

    /** True for `custom` — claims an env only when nothing else did. */
    val fallbackClaim: Boolean
        get() = false

    /** The transform for a claimed env. */
    fun transformer(env: TransformerEnvContext): MinecraftTransformer

    /**
     * Environment-facing contributions independent of the legacy
     * transformer-task pipeline — feature bindings and other metadata
     * production wiring consumes. Runs once per environment before
     * [transformer] selection and at post-DSL environment binding, so
     * implementations must be idempotent and must not register tasks.
     */
    fun contribute(env: TransformerEnvContext) {}

    /** Context a claim receives to build its transform — the
     *  descriptor row (null for rowless custom envs), the env's
     *  declared pipeline/features spec, and the project for task
     *  registration. */
    interface TransformerEnvContext {
        val project: Project
        val descriptor: LoaderDescriptor?
        val env: TransformEnvironmentSpec

        /** The bound MinecraftEnvironment for env-level wiring —
         *  null on rowless claims where no env binding exists. */
        val minecraft: MinecraftEnvironment?
            get() = null
    }

}

/** ServiceLoader SPI — transform modules expose their claims here. */
interface TransformerSource {

    fun transformerClaims(): List<TransformerClaim>

}

/**
 * Registry of [TransformerClaim]s — ServiceLoader builtins plus
 * manual registration. Resolution: explicit push wins (the existing
 * `env.transformer(t)` path), then family match, then the fallback.
 */
class TransformerRegistry {

    private val registered = mutableListOf<TransformerClaim>()

    fun register(claim: TransformerClaim) {
        registered += claim
    }

    val all: List<TransformerClaim>
        get() = builtins() + registered

    /**
     * Resolve the transform for an env — a claim listing the
     * descriptor's family wins; the fallback (`custom`) claims
     * rowless envs and unclaimed families; null when nothing claims.
     */
    fun select(ctx: TransformerClaim.TransformerEnvContext): MinecraftTransformer? =
        selectClaim(ctx)?.transformer(ctx)

    /** Resolve the claiming implementation without instantiating its
     *  transformer — production wiring needs the claim's
     *  [TransformerClaim.contribute] metadata without registering the
     *  legacy transformer task graph. */
    fun selectClaim(ctx: TransformerClaim.TransformerEnvContext): TransformerClaim? {
        val family = ctx.descriptor?.family
        val claims = all
        if (family != null) {
            claims.firstOrNull { family in it.claimedFamilies }
                ?.let { return it }
        }
        return claims.firstOrNull { it.fallbackClaim }
    }

    companion object {

        fun builtins(): List<TransformerClaim> =
            (ServiceLoader.load(
                TransformerSource::class.java,
                TransformerSource::class.java.classLoader
            ) + runCatching {
                ServiceLoader.load(TransformerSource::class.java)
            }.getOrDefault(emptyList()))
                .flatMap { it.transformerClaims() }

    }

}
