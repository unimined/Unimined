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

    /** Global merged-mapping cache root — `null` disables the cache. */
    val globalCacheDir: Path?
        get() = null

    /**
     * Resolve a Maven coordinate (`group:artifact:version[:classifier]`)
     * to a local file — injected by the host; participants that never see
     * remote sources may leave it unimplemented.
     */
    fun resolveMaven(notation: String): Path

    /**
     * Resolve a builtin/provider mapping channel to local files — e.g.
     * Mojang's `client_mappings` for `mojmap`, or a channel's Maven
     * artifact when repository wiring is involved. Injected by the
     * environment host; the default throws because detached/file-only
     * contexts have no channel machinery.
     */
    fun resolveChannel(channel: String, params: Map<String, String>): List<Path> =
        throw UnsupportedOperationException(
            "builtin channel '$channel' is not supported in this context"
        )

}
