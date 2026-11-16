package xyz.wagyourtail.unimined.source.task

import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import org.gradle.api.tasks.Classpath
import xyz.wagyourtail.unimined.mapping.remapper.source.Linemap

/**
 * Applies the vineflower linemap extras embedded in a sources jar to
 * the matching binary jar — 1.x `lineMapJar` port. Produces the
 * `-linemapped` classifier whose `LineNumberTable`s track the
 * decompiled/enriched source lines for debugging. Delegates to UML's
 * [Linemap.applyJar], which pins entry timestamps and preserves
 * non-class extras for reproducible output.
 */
@DisableCachingByDefault(because = "reads live env state at execution — inputs aren't fully declared")
abstract class LinemappedJarTask : DefaultTask() {

    @get:Classpath
    @get:InputFile
    abstract val binaryJar: RegularFileProperty

    @get:Classpath
    @get:InputFile
    abstract val sourcesJar: RegularFileProperty

    @get:OutputFile
    abstract val linemappedJar: RegularFileProperty

    @TaskAction
    fun lineMapJar() {
        val out = linemappedJar.get().asFile
        out.parentFile.mkdirs()
        Linemap.applyJar(
            binaryJar.get().asFile.toPath(),
            sourcesJar.get().asFile.toPath(),
            out.toPath()
        )
    }

}
