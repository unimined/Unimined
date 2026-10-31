package xyz.wagyourtail.unimined.transform.bukkit

import io.sigpipe.jbsdiff.Diff
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import xyz.wagyourtail.unimined.api.mapping.MappingData
import xyz.wagyourtail.unimined.api.util.uniminedLogger
import xyz.wagyourtail.unimined.transform.bukkit.internal.PaperPatchProcessor
import xyz.wagyourtail.unimined.transform.processor.JarProcessor
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class PaperPatchProcessorTest {

    @TempDir
    lateinit var dir: Path

    private val specCtx = object : JarProcessor.SpecContext {
        override val productionNamespace = "spigot"
        override fun mods(): List<JarProcessor.ModJarInfo> = emptyList()
    }

    private val procCtx = object : JarProcessor.ProcessorContext {
        override val logger = uniminedLogger(
            org.gradle.api.logging.Logging.getLogger(PaperPatchProcessorTest::class.java))
        override val mappings: MappingData? = null
        override fun mods(): List<JarProcessor.ModJarInfo> = emptyList()
    }

    private fun distJar(patchEntry: String, patch: ByteArray): Path {
        val jar = dir.resolve("paper-dist.jar")
        ZipOutputStream(Files.newOutputStream(jar)).use { zip ->
            zip.putNextEntry(ZipEntry(patchEntry))
            zip.write(patch)
            zip.closeEntry()
        }
        return jar
    }

    private fun jbsdiffPatch(from: ByteArray, to: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        Diff.diff(from, to, out)
        return out.toByteArray()
    }

    @Test
    fun `spec captures the embedded patch and processJar applies it`() {
        val vanilla = "vanilla-server-bytes".toByteArray()
        val patched = "paper-patched-server-bytes-longer".toByteArray()
        val patch = jbsdiffPatch(vanilla, patched)

        listOf("paperMC.patch", "META-INF/patches/server-1.19.4.patch")
            .forEachIndexed { i, entry ->
            val jar = dir.resolve("server-$i.jar")
            Files.write(jar, vanilla)
            val processor = PaperPatchProcessor { distJar(entry, patch) }
            val spec = processor.buildSpec(specCtx)
            val roundTrip = processor.deserializeSpec(processor.serializeSpec(spec))

            processor.processJar(jar, roundTrip, procCtx)
            assertArrayEquals(patched, Files.readAllBytes(jar), "entry $entry")
        }
    }

    @Test
    fun `dist jar without a patch entry fails loudly`() {
        val processor = PaperPatchProcessor { distJar("META-INF/MANIFEST.MF", byteArrayOf(1)) }
        val error = assertThrows<IllegalStateException> {
            processor.buildSpec(specCtx)
        }
        assertTrue(error.message!!.contains("carries no server patch"))
    }
}
