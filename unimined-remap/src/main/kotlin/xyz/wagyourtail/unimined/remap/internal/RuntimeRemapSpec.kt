package xyz.wagyourtail.unimined.remap.internal

import org.gradle.api.file.FileSystemLocation
import xyz.wagyourtail.unimined.api.spec.RemapSpec
import java.io.File

/**
 * Execution-time [RemapSpec] assembled from a task's resolved inputs.
 */
internal class RuntimeRemapSpec(
    override val sourceNamespace: String,
    override val targetNamespace: String,
    override val classpath: List<FileSystemLocation>
) : RemapSpec

/**
 * Bare [FileSystemLocation] over a resolved [File] — the spec contract
 * wants locations, not `Path`s, and the Gradle-managed impls are not
 * constructible at execution time.
 */
internal class RuntimeLocation(private val file: File) : FileSystemLocation {
    override fun getAsFile(): File = file
}
