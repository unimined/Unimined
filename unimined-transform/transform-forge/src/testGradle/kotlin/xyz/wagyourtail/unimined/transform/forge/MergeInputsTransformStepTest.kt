package xyz.wagyourtail.unimined.transform.forge

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.gradle.api.logging.Logging
import org.junit.jupiter.api.io.TempDir
import xyz.wagyourtail.unimined.api.mapping.MappingData
import xyz.wagyourtail.unimined.api.spec.RemapSpec
import xyz.wagyourtail.commons.core.logger.Logger
import xyz.wagyourtail.unimined.remap.spi.PipelineState
import xyz.wagyourtail.unimined.remap.spi.StepContext
import xyz.wagyourtail.unimined.transform.forge.internal.MergeInputsTransformStep
import xyz.wagyourtail.unimined.transform.step.TransformStepContext
import java.io.File
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import kotlin.io.path.inputStream
import kotlin.io.path.outputStream

/**
 * [MergeInputsTransformStep] — bound inputs overlay the pipeline
 * jar, signature material dropped, base manifest kept.
 */
class MergeInputsTransformStepTest {

    @TempDir
    lateinit var dir: Path

    private fun ctx(inputs: Map<String, List<File>>) = object : TransformStepContext {
        override val workDir = dir.resolve("work").also { java.nio.file.Files.createDirectories(it) }
        override val stepContext = object : StepContext {
            override val state: PipelineState by lazy { PipelineState(spec.sourceNamespace, null) }
            override val spec = object : RemapSpec {
                override val sourceNamespace = "official"
                override val targetNamespace = "named"
                override val classpath: List<Path> = emptyList()
            }
            override val mappings: MappingData? = null
            override val classpath = emptyList<Path>()
            override val workDir = dir
            override val logger: Logger = xyz.wagyourtail.unimined.api.util.uniminedLogger(
                Logging.getLogger(MergeInputsTransformStepTest::class.java)
            )
        }
        override val inputs = inputs
    }

    private fun jar(name: String, entries: Map<String, ByteArray>): Path {
        val jar = dir.resolve(name)
        jar.parent?.let { java.nio.file.Files.createDirectories(it) }
        JarOutputStream(jar.outputStream()).use { out ->
            for ((n, bytes) in entries) {
                out.putNextEntry(JarEntry(n))
                out.write(bytes)
                out.closeEntry()
            }
        }
        return jar
    }

    private fun entries(jar: Path): Map<String, String> =
        JarFile(jar.toFile()).use { z ->
            z.entries().asSequence()
                .filter { !it.isDirectory }
                .associate { it.name to z.getInputStream(it).readBytes().decodeToString() }
        }

    @Test
    fun `no inputs returns input unchanged`() {
        val input = jar("base.jar", mapOf("a/A.class" to byteArrayOf(1)))
        assertSame(input, MergeInputsTransformStep.transform(input, ctx(emptyMap())))
    }

    @Test
    fun `aux entries overlay and win on collision`() {
        val base = jar("base.jar", mapOf(
            "a/A.class" to "base-a".encodeToByteArray(),
            "META-INF/MANIFEST.MF" to "base-manifest".encodeToByteArray(),
            "META-INF/OLD.SF" to "sig".encodeToByteArray(),
        ))
        val aux = jar("server.jar", mapOf(
            "a/A.class" to "server-a".encodeToByteArray(),
            "b/B.class" to "server-b".encodeToByteArray(),
            "META-INF/MANIFEST.MF" to "aux-manifest".encodeToByteArray(),
            "META-INF/X.RSA" to "sig".encodeToByteArray(),
        ))
        val out = MergeInputsTransformStep.transform(
            base, ctx(mapOf("server" to listOf(aux.toFile())))
        )
        val e = entries(out)
        assertEquals("server-a", e["a/A.class"])     // aux wins
        assertEquals("server-b", e["b/B.class"])     // aux-only entry merged
        assertEquals("base-manifest", e["META-INF/MANIFEST.MF"]) // base manifest kept
        assertFalse(e.containsKey("META-INF/OLD.SF")) // base sig dropped
        assertFalse(e.containsKey("META-INF/X.RSA"))  // aux sig dropped
    }

    @Test
    fun `later binding wins over earlier`() {
        val base = jar("base.jar", emptyMap())
        val a = jar("a.jar", mapOf("x/X.class" to "a".encodeToByteArray()))
        val b = jar("b.jar", mapOf("x/X.class" to "b".encodeToByteArray()))
        val out = MergeInputsTransformStep.transform(
            base, ctx(linkedMapOf("first" to listOf(a.toFile()), "second" to listOf(b.toFile())))
        )
        assertEquals("b", entries(out)["x/X.class"])
    }

}
