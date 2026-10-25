package xyz.wagyourtail.unimined.api.spi

/**
 * One selector per SPI host: resolves a single implementation from the
 * registered candidates, or errors on zero/ambiguous matches. A DSL
 * override short-circuits capability negotiation entirely.
 *
 * Instances are created via [spiSelector].
 *
 * @param T the SPI participant type
 * @param S the spec type the participant is selected against
 */
interface SpiSelector<T : Supports<S>, S> {

    /** Register a candidate implementation. Duplicate names are rejected. */
    fun register(impl: T)

    /**
     * Resolve the implementation to use for [spec].
     *
     * @param override when non-null, the [Named.name] of the implementation
     *  to use, bypassing [Supports.supports]
     * @throws SpiSelectionException on an unknown override, zero supporting
     *  candidates, or ambiguous candidates
     */
    fun select(spec: S, override: String? = null): T

}

/**
 * Create the shared selection machinery for an SPI host.
 *
 * @param kind human-readable name of the SPI kind ("minecraft provider",
 *  "remap step", ...) used in error messages
 */
fun <T : Supports<S>, S> spiSelector(kind: String): SpiSelector<T, S> = SpiSelectorImpl(kind)
