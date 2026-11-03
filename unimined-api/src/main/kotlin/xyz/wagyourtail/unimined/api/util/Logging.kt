package xyz.wagyourtail.unimined.api.util

import org.gradle.api.logging.Logger
import xyz.wagyourtail.commons.core.logger.Logger as CommonsLogger
import xyz.wagyourtail.commons.core.logger.prefix.DefaultLoggingPrefix
import xyz.wagyourtail.commons.gradle.GradleLogWrapper

/**
 * `[Unimined]` root logger — descend with [CommonsLogger.subLogger] to
 * build env/subsystem/step scopes (`[Unimined/<env>/<step>]`) so lines
 * from concurrent environments/transforms stay attributable.
 */
fun uniminedLogger(logger: Logger): CommonsLogger =
    GradleLogWrapper(
        DefaultLoggingPrefix.builder()
            .loggerName("Unimined")
            .includeTime(false).includeLevel(false)
            .includeClassName(false).includeThreadName(false)
            .build(),
        logger
    )

/** Env-scoped logger — `[Unimined/<envId>]`; `/` in [envId] nests. */
fun uniminedLogger(envId: String, logger: Logger): CommonsLogger =
    envId.split('/').fold(uniminedLogger(logger)) { l, s -> l.subLogger(s) }
