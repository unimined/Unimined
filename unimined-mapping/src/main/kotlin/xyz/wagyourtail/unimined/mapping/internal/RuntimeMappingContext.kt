package xyz.wagyourtail.unimined.mapping.internal

import org.gradle.api.logging.Logger
import xyz.wagyourtail.unimined.api.mapping.MappingContext
import xyz.wagyourtail.unimined.api.spec.Side
import java.nio.file.Path

internal class RuntimeMappingContext(
    override val environmentId: String,
    override val logger: Logger,
    override val cacheDir: Path?,
    override val globalCacheDir: Path? = null,
    private val mavenResolver: (String) -> Path,
    private val channelResolver: (String, Map<String, String>) -> List<Path> = { channel, _ ->
        throw UnsupportedOperationException(
            "builtin channel '$channel' is not supported in this context"
        )
    },
    override val envSide: Side? = null
) : MappingContext {

    override fun resolveMaven(notation: String): Path = mavenResolver(notation)

    override fun resolveChannel(channel: String, params: Map<String, String>): List<Path> =
        channelResolver(channel, params)

}
