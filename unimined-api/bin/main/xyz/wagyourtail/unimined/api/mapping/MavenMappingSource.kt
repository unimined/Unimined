package xyz.wagyourtail.unimined.api.mapping

/** A declared source resolved from a Maven coordinate. */
interface MavenMappingSource : MappingSource {

    /** Dependency notation, e.g. `group:artifact:version[:classifier]`. */
    val notation: String

}
