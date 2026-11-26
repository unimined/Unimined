package xyz.wagyourtail.unimined.mapping.internal

import okio.buffer
import okio.source
import xyz.wagyourtail.unimined.mapping.formats.FormatProvider
import xyz.wagyourtail.unimined.mapping.formats.FormatRegistry
import java.nio.file.Path
import kotlin.io.path.inputStream
import kotlin.io.path.name

/**
 * Sniff a materialized mapping file's [FormatProvider] — binary `UMFB`
 * payloads by magic (they carry no [FormatRegistry] reader contract),
 * everything else through [FormatRegistry.autodetectFormat]. A non-null
 * [hint] wins over autodetect, mirroring how a declaration's
 * `formatHint` overrides per-file detection during reads.
 */
internal fun detectUmlFormat(path: Path, hint: String? = null): FormatProvider? {
    if (hint != null) return FormatRegistry.byName[hint]
    return runCatching {
        path.inputStream().source().buffer().use { src ->
            val peek = src.peek()
            if (peek.request(4) && peek.readUtf8(4) == "UMFB") {
                FormatRegistry.byName["umf"]
            } else {
                FormatRegistry.autodetectFormat(path.name, src)
            }
        }
    }.getOrNull()
}
