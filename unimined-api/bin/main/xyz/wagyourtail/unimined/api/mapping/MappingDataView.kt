package xyz.wagyourtail.unimined.api.mapping

/**
 * A typed view over resolved mapping data that an SPI participant may
 * request — e.g. a namespace→namespace remap tree, nests, access
 * wideners/transformers, generic signatures, exceptions.
 *
 * Steps and transforms declare which views they need rather than
 * receiving one canonical blob; the mappings core produces whichever
 * views the declared consumers ask for.
 */
interface MappingDataView
