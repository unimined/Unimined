package xyz.wagyourtail.unimined.api.util

import org.gradle.api.Project
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.io.path.createDirectories

/**
 * Cross-module phase timing for the perf megaplan (`Docs/perf-plan.md`).
 *
 * Enabled by the `unimined.perf` Gradle property (mirrored to a system
 * property by [syncFrom] so Gradle-free core code paths see it), a
 * `-Dunimined.perf` JVM arg, or the `UNIMINED_PERF` env var. Events are
 * JSON lines appended to the `unimined.perf.file` target (default
 * `<root>/build/unimined/perf.jsonl` when synced from Gradle;
 * `UNIMINED_PERF_FILE` env or `-Dunimined.perf.file` wins).
 *
 * Never fails a build — every public entry point swallows errors.
 */
object Perf {

    const val PROP = "unimined.perf"
    const val PROP_FILE = "unimined.perf.file"

    /** Marker for "we set the sysprops" — so a later non-perf apply can
     * clear them instead of leaking stale state across daemon builds. */
    private const val MANAGED = "unimined.perf.managed"

    @Volatile
    private var resolved = false

    @Volatile
    private var file: Path? = null

    private val writer = Any()

    private fun truthy(v: String?): Boolean =
        v != null && v != "false" && v != "0" && v != "off"

    private fun resolve() {
        if (resolved) return
        synchronized(writer) {
            if (resolved) return
            val enabled = truthy(System.getProperty(PROP)) ||
                    truthy(System.getenv("UNIMINED_PERF"))
            val target = System.getProperty(PROP_FILE)
                ?: System.getenv("UNIMINED_PERF_FILE")
            file = if (enabled && !target.isNullOrBlank()) {
                runCatching {
                    Path.of(target).toAbsolutePath().also {
                        it.parent?.createDirectories()
                    }
                }.getOrNull()
            } else null
            resolved = true
        }
    }

    /** Whether event writing is active. Cheap once [resolve] ran. */
    val enabled: Boolean
        get() {
            resolve()
            return file != null
        }

    /**
     * Mirror the `unimined.perf`/`unimined.perf.file` Gradle properties
     * into system properties — call once per plugin `apply` on any
     * plugin that hosts instrumented paths. Idempotent; a direct
     * `-Dunimined.perf` always wins.
     */
    fun syncFrom(project: Project) {
        runCatching {
            val prop = project.providers.gradleProperty(PROP).orNull
            val managed = System.getProperty(MANAGED) == "true"
            if (prop != null) {
                if (managed || System.getProperty(PROP) == null) {
                    System.setProperty(PROP, prop)
                    System.setProperty(MANAGED, "true")
                }
                if (managed || System.getProperty(PROP_FILE) == null) {
                    val explicit = project.providers.gradleProperty(PROP_FILE).orNull
                    val target = explicit
                        ?: project.rootProject.layout.buildDirectory
                            .dir("unimined/perf.jsonl").get().asFile.absolutePath
                    System.setProperty(PROP_FILE, target)
                }
            } else if (managed) {
                // previous perf-enabled apply set these; clear so a plain
                // build in the same daemon doesn't emit to a stale path
                System.clearProperty(PROP)
                System.clearProperty(PROP_FILE)
                System.clearProperty(MANAGED)
            }
            // a resolved decision from an earlier invocation must not
            // stick — the daemon outlives invocations, and `-D` sysprops
            // reach the daemon without ever appearing as a Gradle
            // property (prop == null), so the reset can't live inside
            // the prop!=null branch alone
            resetForTest()
        }
    }

    /** Reset the resolved flag — tests only. */
    @JvmStatic
    fun resetForTest() {
        synchronized(writer) {
            resolved = false
            file = null
        }
    }

    /** Emit a point-in-time event ([ms] carried on the `ms` tag). */
    fun emit(event: String, vararg tags: Pair<String, Any?>) {
        resolve()
        val f = file ?: return
        runCatching {
            val line = buildString(tags.size * 24 + 48) {
                append("{\"ts\":").append(System.currentTimeMillis())
                append(",\"ev\":\"").append(event).append('"')
                for ((k, v) in tags) {
                    if (v == null) continue
                    append(',').append('"').append(k).append("\":")
                    when (v) {
                        is Number, is Boolean -> append(v)
                        else -> append('"').append(escape(v.toString())).append('"')
                    }
                }
                append("}\n")
            }
            synchronized(writer) {
                Files.write(
                    f, line.toByteArray(Charsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND
                )
            }
        }
    }

    /** Time [block]; emits `<event>` with `ms` on completion (or `error`). */
    inline fun <T> span(
        event: String,
        vararg tags: Pair<String, Any?>,
        block: () -> T
    ): T {
        if (!enabled) return block()
        val t0 = System.nanoTime()
        try {
            val result = block()
            emit(event, *tags, "ms" to (System.nanoTime() - t0) / 1_000_000)
            return result
        } catch (t: Throwable) {
            emit(event, *tags, "ms" to (System.nanoTime() - t0) / 1_000_000,
                "error" to (t.message ?: t.javaClass.simpleName))
            throw t
        }
    }

    private fun escape(s: String): String = buildString(s.length + 8) {
        for (ch in s) when (ch) {
            '"', '\\' -> append('\\').append(ch)
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(ch)
        }
    }

}
