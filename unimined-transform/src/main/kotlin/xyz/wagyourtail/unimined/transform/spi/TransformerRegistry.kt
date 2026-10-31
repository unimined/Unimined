package xyz.wagyourtail.unimined.transform.spi

import java.util.ServiceLoader
import org.gradle.api.Project
import xyz.wagyourtail.unimined.transform.TransformEnvironmentSpec
import xyz.wagyourtail.unimined.transform.loader.LoaderDescriptor

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

    /** Context a claim receives to build its transform — the
     *  descriptor row (null for rowless custom envs), the env's
     *  declared pipeline/features spec, and the project for task
     *  registration. */
    interface TransformerEnvContext {
        val project: Project
        val descriptor: LoaderDescriptor?
        val env: TransformEnvironmentSpec
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
    fun select(ctx: TransformerClaim.TransformerEnvContext): MinecraftTransformer? {
        val family = ctx.descriptor?.family
        val claims = all
        if (family != null) {
            claims.firstOrNull { family in it.claimedFamilies }
                ?.let { return it.transformer(ctx) }
        }
        return claims.firstOrNull { it.fallbackClaim }?.transformer(ctx)
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
