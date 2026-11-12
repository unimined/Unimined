package xyz.wagyourtail.unimined.remap.spi

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import xyz.wagyourtail.commons.core.logger.JavaLogWrapper
import xyz.wagyourtail.commons.core.logger.Logger
import xyz.wagyourtail.unimined.api.mapping.ViewMapMappingData
import xyz.wagyourtail.unimined.api.spec.RemapSpec
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.zip.ZipFile
import kotlin.io.path.outputStream

class StepPipelineTest {

    @TempDir
    lateinit var dir: Path

    private fun ctx(): StepContext = object : StepContext {
        override val surface = RemapSurface.JAR
        override val state = PipelineState("official", null)
        override val spec = object : RemapSpec {
            override val sourceNamespace = "official"
            override val targetNamespace = "named"
            override val classpath: List<Path> = emptyList()
        }
        override val mappings = ViewMapMappingData(emptyMap())
        override val classpath: List<Path> = emptyList()
        override val workDir: Path = dir.resolve("work")
        override val logger: Logger =
            JavaLogWrapper(java.util.logging.Logger.getLogger("test"))
    }

    private fun jar(path: Path, entries: Map<String, String>) {
        Files.createDirectories(path.parent)
        JarOutputStream(Files.newOutputStream(path)).use { out ->
            entries.forEach { (name, content) ->
                out.putNextEntry(JarEntry(name))
                out.write(content.toByteArray())
                out.closeEntry()
            }
        }
    }

    private fun entries(jar: Path): Map<String, String> =
        ZipFile(jar.toFile()).use { zip ->
            zip.entries().toList().filter { !it.isDirectory }.associate {
                it.name to zip.getInputStream(it).readBytes().toString(Charsets.UTF_8)
            }
        }

    private fun markerStep(
        tag: String,
        seenInputs: MutableList<String>,
        transform: (FusedJarEntry) -> FusedJarEntry? = { it }
    ) = object : FusibleJarStep {
        override fun prepareFused(input: Path, ctx: StepContext) {
            seenInputs += "$tag:${input.fileName}"
        }
        override fun visitEntry(entry: FusedJarEntry, ctx: StepContext) =
            transform(entry)
    }

    @Test
    fun `consecutive fusible steps share one traversal`() {
        val input = dir.resolve("in.jar")
        jar(input, mapOf("a.txt" to "alpha", "b.txt" to "beta"))
        val seen = mutableListOf<String>()

        val first = markerStep("first", seen) {
            FusedJarEntry(it.name, it.bytes.map { b -> (b - 32).toByte() }.toByteArray())
        }
        val second = markerStep("second", seen) {
            FusedJarEntry(it.name, it.bytes + "!".toByteArray())
        }

        val out = StepPipeline.run(input, listOf(first, second), ctx())
        // both visitors saw the ORIGINAL input in prepareFused —
        // proof of one shared traversal rather than chained jars
        assertEquals(listOf("first:in.jar", "second:in.jar"), seen)
        val outEntries = entries(out)
        assertEquals("ALPHA!", outEntries["a.txt"])
        assertEquals("BETA!", outEntries["b.txt"])
    }

    @Test
    fun `a non-fusible step breaks the group at its position`() {
        val input = dir.resolve("in.jar")
        jar(input, mapOf("a.txt" to "x"))
        val seen = mutableListOf<String>()
        val nonFusibleOut = dir.resolve("work/nf.jar")

        val before = markerStep("before", seen)
        val nonFusible = ArtifactStep { i, _ ->
            nonFusibleOut.parent.toFile().mkdirs()
            // copy-through marker so we can tell it ran
            java.util.zip.ZipFile(i.toFile()).use { zip ->
                JarOutputStream(nonFusibleOut.outputStream()).use { out ->
                    zip.entries().toList().forEach { e ->
                        out.putNextEntry(JarEntry(e.name))
                        zip.getInputStream(e).copyTo(out)
                        out.closeEntry()
                    }
                }
            }
            nonFusibleOut
        }
        val after = markerStep("after", seen)

        val out = StepPipeline.run(input, listOf(before, nonFusible, after), ctx())
        // `before` fused alone saw the original input; `after` saw the
        // non-fusible step's output — two separate traversals
        assertEquals("before:in.jar", seen[0])
        assertEquals("after:nf.jar", seen[1])
        assertEquals("x", entries(out)["a.txt"])
    }

    @Test
    fun `a visitor returning null drops the entry`() {
        val input = dir.resolve("in.jar")
        jar(input, mapOf("keep.txt" to "k", "drop.txt" to "d"))
        val drop = FusibleJarStep { e, _ ->
            if (e.name == "drop.txt") null else e
        }
        val out = StepPipeline.run(input, listOf(drop), ctx())
        val outEntries = entries(out)
        assertEquals(setOf("keep.txt"), outEntries.keys)
    }

    @Test
    fun `an emit tap writes the artifact state at its position`() {
        val input = dir.resolve("in.jar")
        jar(input, mapOf("a.txt" to "alpha", "dir/b.txt" to "beta"))
        val emitted = dir.resolve("emitted.jar")
        val upper = FusibleJarStep { e, _ ->
            FusedJarEntry(e.name, e.bytes.map { b -> (b - 32).toByte() }.toByteArray(), e.time)
        }
        val bang = FusibleJarStep { e, _ ->
            FusedJarEntry(e.name, e.bytes + "!".toByteArray(), e.time)
        }
        val emit = xyz.wagyourtail.unimined.remap.internal.EmitArtifactStepType()
            .create(EmitArtifactStepSpec(emitted.toString()), ctx())

        val out = StepPipeline.run(input, listOf(upper, emit, bang), ctx())
        // emitted artifact is the mid-state — post-upper, pre-bang
        assertEquals("ALPHA", entries(emitted)["a.txt"])
        assertEquals("BETA", entries(emitted)["dir/b.txt"])
        // group output has the full chain
        assertEquals("ALPHA!", entries(out)["a.txt"])
    }

    @Test
    fun `a visitor can rename entries`() {
        val input = dir.resolve("in.jar")
        jar(input, mapOf("old.txt" to "data"))
        val rename = FusibleJarStep { e, _ ->
            FusedJarEntry("new.txt", e.bytes)
        }
        val out = StepPipeline.run(input, listOf(rename), ctx())
        val outEntries = entries(out)
        assertEquals(mapOf("new.txt" to "data"), outEntries)
    }

}
