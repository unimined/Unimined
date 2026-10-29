package xyz.wagyourtail.unimined.minecraft.task

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.options.Option

/**
 * Prunes unimined-owned cache directories.
 *
 * Global root (`~/.gradle/caches/unimined`, or project `.gradle` when
 * `unimined.useGlobalCache=false`) entries whose newest file is older
 * than [maxAgeDays] are deleted — the mtime window doubles as the
 * "in use by a live daemon" guard. `assets/` is skipped by default:
 * it's content-addressed and pruning buys nothing (`--assets` opts
 * in). `--project` additionally wipes this project's
 * `.gradle/unimined` — always safe, it regenerates lazily.
 *
 * Gradle's own `transforms-*` cache is GC'd by Gradle and untouched.
 */
abstract class PruneUniminedCacheTask : DefaultTask() {

    /** Global unimined cache root ([xyz.wagyourtail.unimined.api.util.UniminedCache.global]). */
    @get:Internal
    abstract val globalCacheDir: DirectoryProperty

    /** Project unimined cache root (`<project>/.gradle/unimined`). */
    @get:Internal
    abstract val projectCacheDir: DirectoryProperty

    /** Global entries untouched within this many days are kept; `0`
     *  removes everything. Convention: `unimined.pruneAgeDays`, `30`. */
    @get:Input
    abstract val maxAgeDays: Property<Int>

    @get:Input
    @get:Optional
    abstract val pruneProject: Property<Boolean>

    @get:Input
    @get:Optional
    abstract val includeAssets: Property<Boolean>

    @Option(option = "age-days", description = "Prune global entries idle longer than this many days (0 = all)")
    fun ageDays(days: String) {
        maxAgeDays.set(days.toInt())
    }

    @Option(option = "project", description = "Also wipe this project's .gradle/unimined")
    fun project(enabled: Boolean) {
        pruneProject.set(enabled)
    }

    @Option(option = "assets", description = "Also prune the shared assets dir")
    fun assets(enabled: Boolean) {
        includeAssets.set(enabled)
    }

    @TaskAction
    fun prune() {
        var freed = 0L
        var removed = 0

        val global = globalCacheDir.get().asFile
        if (global.isDirectory) {
            val cutoff = maxAgeDays.get().takeIf { it > 0 }
                ?.let { System.currentTimeMillis() - it * 86_400_000L }
            for (entry in global.listFiles().orEmpty()) {
                if (entry.name == "assets" && !includeAssets.getOrElse(false)) {
                    logger.info("[pruneUniminedCache] keeping assets/ (content-addressed; --assets to include)")
                    continue
                }
                if (cutoff != null) {
                    val newest = entry.walkTopDown()
                        .filter { it.isFile }
                        .maxOfOrNull { it.lastModified() } ?: 0L
                    if (newest >= cutoff) continue
                }
                val bytes = entry.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                entry.deleteRecursively()
                freed += bytes
                removed++
                logger.lifecycle("[pruneUniminedCache] pruned ${entry.name} (${bytes / 1_048_576} MiB)")
            }
        }

        if (pruneProject.getOrElse(false)) {
            val proj = projectCacheDir.get().asFile
            if (proj.isDirectory) {
                val bytes = proj.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                proj.deleteRecursively()
                freed += bytes
                removed++
                logger.lifecycle("[pruneUniminedCache] wiped project .gradle/unimined (${bytes / 1_048_576} MiB)")
            }
        }

        if (removed == 0) {
            logger.lifecycle("[pruneUniminedCache] nothing to prune")
        } else {
            logger.lifecycle("[pruneUniminedCache] freed ${freed / 1_048_576} MiB across $removed roots")
        }
    }

}
