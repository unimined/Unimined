package xyz.wagyourtail.unimined.loadermerge.task

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import xyz.wagyourtail.unimined.loadermerge.ModLoaderMergerRegistry
import java.nio.file.Files

/**
 * Invokes the selected [xyz.wagyourtail.unimined.loadermerge.ModLoaderMerger]
 * engine over named per-loader jars and emits the `merged` artifact.
 *
 * [inputNames] and [loaderJars] are parallel lists — Gradle tracks files,
 * not maps, so names ride a separate [ListProperty]; [merge] zips them
 * back into the `Map<String, Path>` the SPI consumes.
 */
@CacheableTask
abstract class MergeLoaderJarsTask : DefaultTask() {

    /** All input jars (every engine), for up-to-date tracking. */
    @get:InputFiles
    @get:Classpath
    abstract val loaderJars: ConfigurableFileCollection

    /** Loader names parallel to [loaderJars] insertion order. */
    @get:Input
    abstract val inputNames: ListProperty<String>

    /** Engine name; absent → sole registered engine. */
    @get:Input
    @get:Optional
    abstract val engine: Property<String>

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
        val out = outputJar.get().asFile.toPath()
        Files.createDirectories(out.parent)
        ModLoaderMergerRegistry.resolve(engine.orNull)
            .merge(names.zip(jars).associate { (n, f) -> n to f.toPath() }, out)
        check(Files.exists(out)) {
            "loader merger '${engine.orNull ?: "default"}' produced no " +
                    "output at $out"
        }
    }

}
