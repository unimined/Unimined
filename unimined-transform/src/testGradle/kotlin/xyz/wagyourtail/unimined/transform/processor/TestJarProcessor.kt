package xyz.wagyourtail.unimined.transform.processor

import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream

/**
 * ServiceLoader'd test [JarProcessor] — its spec payload is an opaque
 * string passed through verbatim, and `processJar` appends a
 * `test-processor.marker` entry so the step's mutation is observable.
 */
class TestJarProcessor : JarProcessor {

    override val name = "test-processor"

    class Spec(val payload: String) : JarProcessor.JarProcessorSpec

    override fun buildSpec(context: JarProcessor.SpecContext) = Spec("test")

    override fun serializeSpec(spec: JarProcessor.JarProcessorSpec) = (spec as Spec).payload

    override fun deserializeSpec(payload: String) = Spec(payload)

    override fun processJar(
        jar: Path, spec: JarProcessor.JarProcessorSpec,
        context: JarProcessor.ProcessorContext
    ) {
        calls += jar to (spec as Spec).payload
        val entries = JarFile(jar.toFile()).use { j ->
            j.entries().asSequence()
                .filter { !it.isDirectory }
                .map { it.name to j.getInputStream(it).readBytes() }
                .toList()
        }
        JarOutputStream(Files.newOutputStream(jar)).use { out ->
            for ((entryName, bytes) in entries) {
                out.putNextEntry(JarEntry(entryName))
                out.write(bytes)
                out.closeEntry()
            }
            out.putNextEntry(JarEntry(MARKER))
            out.write(byteArrayOf(1))
            out.closeEntry()
        }
    }

    companion object {
        const val MARKER = "test-processor.marker"
        val calls = mutableListOf<Pair<Path, String>>()
        fun reset() = calls.clear()
    }

}
