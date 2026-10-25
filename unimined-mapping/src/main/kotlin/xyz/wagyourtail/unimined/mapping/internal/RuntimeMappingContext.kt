package xyz.wagyourtail.unimined.mapping.internal

import org.gradle.api.logging.Logger
import xyz.wagyourtail.unimined.api.mapping.MappingContext
import java.nio.file.Path

internal class RuntimeMappingContext(
    override val environmentId: String,
    override val logger: Logger,
    override val cacheDir: Path?,
    private val mavenResolver: (String) -> Path
) : MappingContext {

    override fun resolveMaven(notation: String): Path = mavenResolver(notation)

}
