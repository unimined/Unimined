package xyz.wagyourtail.unimined.api.mapping

/**
 * Normalizes or enriches composed [MappingData] after the composer runs
 * and before the result is consumed or serialized — e.g. `<init>` cell
 * completion, unobfuscated-namespace completion, signature fixes.
 *
 * Implementations are discovered via
 * `META-INF/services/xyz.wagyourtail.unimined.api.mapping.MappingPostProcessor`,
 * run in registry order, and must be in-place-or-replace: [process]
 * returns the data to hand downstream, which may be the same instance
 * (in-place mutation) or a new `MappingData` (view replacement).
 */
interface MappingPostProcessor {

    /** Stable id used in diagnostics and ordering decisions. */
    val name: String

    /**
     * Apply this stage to [data] — no-op-safe: implementations return
     * [data] unchanged when their target view is absent.
     */
    fun process(data: MappingData, ctx: MappingContext): MappingData

}
