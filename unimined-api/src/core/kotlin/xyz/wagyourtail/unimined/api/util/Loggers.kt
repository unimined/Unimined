package xyz.wagyourtail.unimined.api.util

import xyz.wagyourtail.commons.core.logger.Logger as CommonsLogger

/**
 * `[Unimined/<envId>]`-scoped view of an existing commons [CommonsLogger] —
 * the Gradle-free variant of the `uniminedLogger` helpers in `util/Logging.kt`
 * (which wrap a Gradle `Logger` via `GradleLogWrapper`). `/` in [envId] nests.
 */
fun uniminedLogger(envId: String, logger: CommonsLogger): CommonsLogger =
    envId.split('/').fold(logger) { l, s -> l.subLogger(s) }
