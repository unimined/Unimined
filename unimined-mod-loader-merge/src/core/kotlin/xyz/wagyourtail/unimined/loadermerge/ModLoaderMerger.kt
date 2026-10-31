package xyz.wagyourtail.unimined.loadermerge

import java.nio.file.Path
import java.util.ServiceLoader
import java.util.concurrent.ConcurrentHashMap

/**
 * Merger engine SPI — consumes named per-loader mod jars and emits a
 * single merged jar. Implementations are contributed by
 * `mod-loader-merge-*` adapter modules (e.g. Forgix) via
 * [ServiceLoader]; the host plugin selects one by [name] or uses the
 * sole registered engine when no name is requested.
 */
interface ModLoaderMerger {

    /** Engine identifier used in `loaderMerge { engine("...") }`. */
    val name: String

    /**
     * Merge [inputs] (loader name → jar) into [output]. [output]'s
     * parent directory exists; the file itself does not yet.
     */
    fun merge(inputs: Map<String, Path>, output: Path)

}

/** ServiceLoader-backed [ModLoaderMerger] lookup, shared by task and tests. */
object ModLoaderMergerRegistry {

    private val cache = ConcurrentHashMap<String, ModLoaderMerger>()

    private fun engines(): List<ModLoaderMerger> =
        ServiceLoader.load(
            ModLoaderMerger::class.java,
            ModLoaderMerger::class.java.classLoader
        ).toList()

    /**
     * Resolve the engine for [name], or the only registered engine when
     * [name] is null. Errors name the available engines for diagnostics.
     */
    fun resolve(name: String? = null): ModLoaderMerger =
        cache.computeIfAbsent(name ?: "") {
            val all = engines()
            if (name == null) {
                all.singleOrNull()
                    ?: error(
                        "no unambiguous ModLoaderMerger engine " +
                                "(registered: ${all.joinToString(", ") { it.name }.ifEmpty { "none" }}); " +
                                "set loaderMerge.engine"
                    )
            } else {
                all.firstOrNull { it.name == name }
                    ?: error(
                        "no ModLoaderMerger engine named '$name' " +
                                "(registered: ${all.joinToString(", ") { it.name }.ifEmpty { "none" }})"
                    )
            }
        }

}
