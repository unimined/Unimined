package xyz.wagyourtail.unimined.api.minecraft.task

import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.bundling.Jar
import xyz.wagyourtail.unimined.mapping.Namespace
import xyz.wagyourtail.unimined.util.FinalizeOnRead

/**
 * task responsible for transforming your built jar to production.
 * @since 0.1.0
 */
abstract class AbstractRemapJarTask : Jar() {

    @get:InputFile
    abstract val inputFile: RegularFileProperty

    @get:Internal
    @set:Internal
    var devNamespace: Namespace? by FinalizeOnRead(null)

    @get:Internal
    @set:Internal
    var prodNamespace: Namespace? by FinalizeOnRead(null)

    abstract fun devNamespace(namespace: String)

    @Deprecated(message = "no longer needed", replaceWith = ReplaceWith(""))
    fun devFallbackNamespace(namespace: String) {}

    abstract fun prodNamespace(namespace: String)

}