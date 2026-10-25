package xyz.wagyourtail.unimined.api.spi

/**
 * Capability declaration — implemented by every SPI participant.
 *
 * The participant declares whether it can handle a given [spec]; the host
 * plugin's [SpiSelector] uses this to negotiate which implementation serves
 * a request when the DSL does not name one explicitly.
 *
 * @param S the spec type this participant is asked about
 */
interface Supports<in S> : Named {

    /**
     * @return `true` if this implementation can serve [spec]
     */
    fun supports(spec: S): Boolean

}
