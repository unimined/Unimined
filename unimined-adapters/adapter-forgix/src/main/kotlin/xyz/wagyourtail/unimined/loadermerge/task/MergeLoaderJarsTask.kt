package xyz.wagyourtail.unimined.loadermerge.task

import org.gradle.work.DisableCachingByDefault
import io.github.pacifistmc.forgix.Forgix
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.*
import java.nio.file.Files

/**
 * Merges named per-loader jars into the `merged` artifact via Forgix's
 * core engine (`Forgix.mergeLoaders`).
 *
 * Forgix's own `mergeJars` task only works when every merge name is a
 * real subproject name (its `getBestOutputFile` null-checks the
 * project before consulting `inputJar`) — flat unimined projects can't
 * use it. This task drives the layout-agnostic engine directly so
 * merging works on env-per-source-set projects too.
 *
 * [inputNames] and [loaderJars] are parallel lists — Gradle tracks
 * files, not maps, so names ride a separate [ListProperty]; [merge]
 * zips them back into the `Map<String, Path>` Forgix consumes.
 */
@DisableCachingByDefault
abstract class MergeLoaderJarsTask : DefaultTask() {

    /** All input jars, for up-to-date tracking. */
    @get:InputFiles
    @get:Classpath
    abstract val loaderJars: ConfigurableFileCollection

    /** Loader names parallel to [loaderJars] insertion order. */
    @get:Input
    abstract val inputNames: ListProperty<String>

    /** Merged output artifact. */
    @get:OutputFile
    abstract val outputJar: RegularFileProperty

    @TaskAction
    fun merge() {
        val jars = loaderJars.files.toList()
        val names = inputNames.get()
        require(jars.size == names.size) {
            "loaderMerge inputs/name mismatch: ${names.size} names, " +
                    "${jars.size} jars"
        }
        val out = outputJar.get().asFile
        Files.createDirectories(out.toPath().parent)
        Forgix.mergeLoaders(
            names.zip(jars).associate { (n, f) -> f to n }
                .toMap(java.util.HashMap()),
            out
        )
        check(out.exists()) { "forgix produced no output at $out" }
    }

}
