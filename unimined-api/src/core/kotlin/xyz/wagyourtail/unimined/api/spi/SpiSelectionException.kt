package xyz.wagyourtail.unimined.api.spi

/**
 * Thrown when SPI selection cannot resolve a single implementation:
 * an unknown [Named.name] override, zero supporting candidates, or more
 * than one supporting candidate with no override to disambiguate.
 */
class SpiSelectionException(message: String) : RuntimeException(message)
