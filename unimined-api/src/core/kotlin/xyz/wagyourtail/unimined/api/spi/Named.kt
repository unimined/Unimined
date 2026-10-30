package xyz.wagyourtail.unimined.api.spi

/**
 * A named component. Every SPI participant (provider, remapper engine,
 * transform, decompiler engine) carries a stable name so the DSL can
 * override selection by referring to it, e.g. `provider = "mavenizer"`.
 */
interface Named {

    /** Stable identifier used for DSL overrides and error messages. */
    val name: String

}
