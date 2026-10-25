package xyz.wagyourtail.unimined.api.mapping

import org.gradle.api.logging.Logger
import java.nio.file.Path

/**
 * Environment-scoped services handed to mapping SPI participants —
 * source providers, readers, and composers.
 */
interface MappingContext {

    /** The environment this resolution serves, normally the source-set name. */
    val environmentId: String

    val logger: Logger

    /** Shared cache dir a participant may materialize into, when provided. */
    val cacheDir: Path?

    /**
     * Resolve a Maven coordinate (`group:artifact:version[:classifier]`)
     * to a local file — injected by the host; participants that never see
     * remote sources may leave it unimplemented.
     */
    fun resolveMaven(notation: String): Path

}
