package xyz.wagyourtail.unimined.remap.internal

import org.gradle.api.logging.Logging
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import xyz.wagyourtail.unimined.api.spec.RemapSpec
import xyz.wagyourtail.unimined.api.util.uniminedLogger
import xyz.wagyourtail.unimined.remap.spi.FusedJarEntry
import xyz.wagyourtail.unimined.remap.spi.FusibleJarStep
import xyz.wagyourtail.unimined.remap.spi.PipelineState
import xyz.wagyourtail.unimined.remap.spi.RemapSurface
import xyz.wagyourtail.unimined.remap.spi.StepContext
import xyz.wagyourtail.unimined.remap.spi.StripNestedJarsStepSpec
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Dep-pipeline `strip-nested-jars`: `*.mod.json` JiJ declarations die
 * on the way into the dev classpath (their embedded jars were never
 * remapped); everything else passes byte-verbatim.
 */
class StripNestedJarsStepTypeTest {

    @TempDir
    lateinit var dir: Path

    private fun ctx(): StepContext = object : StepContext {
        override val surface = RemapSurface.JAR
        override val state: PipelineState by lazy {
            PipelineState("official", null)
        }
        override val spec = object : RemapSpec {
            override val sourceNamespace = "intermediary"
            override val targetNamespace = "named"
            override val classpath = emptyList<Path>()
        }
        override val mappings = null
        override val classpath = emptyList<Path>()
        override val workDir = dir
        override val logger = uniminedLogger(
            Logging.getLogger(StripNestedJarsStepTypeTest::class.java))
    }

    private fun step(): FusibleJarStep =
        StripNestedJarsStepType().create(
            StripNestedJarsStepSpec(), ctx()) as FusibleJarStep

    @Test
    fun `fabric jars key is dropped`() {
        val fmj = """{
            "id": "depmod",
            "jars": [{"file": "META-INF/jars/inner-1.0.jar"}],
            "custom": {"keep": true}
        }"""
        val out = step().visitEntry(
            FusedJarEntry("fabric.mod.json", fmj.toByteArray()), ctx())
        val text = out!!.bytes.toString(Charsets.UTF_8)
        assertFalse(text.contains("\"jars\""))
        assertTrue(text.contains("\"depmod\""))
        assertTrue(text.contains("\"keep\""))
    }

    @Test
    fun `quilt_loader jars key is dropped`() {
        val qmj = """{
            "quilt_loader": {
                "id": "depmod",
                "jars": [{"file": "META-INF/jars/inner.jar"}]
            }
        }"""
        val out = step().visitEntry(
            FusedJarEntry("quilt.mod.json", qmj.toByteArray()), ctx())
        val text = out!!.bytes.toString(Charsets.UTF_8)
        assertFalse(text.contains("\"jars\""))
        assertTrue(text.contains("\"depmod\""))
    }

    @Test
    fun `non metadata and unchanged entries pass through`() {
        val s = step()
        // visitEntry returning the SAME entry keeps it; null would drop
        val bin = FusedJarEntry("cls.class", byteArrayOf(1, 2, 3))
        assertSame(bin, s.visitEntry(bin, ctx()))
        val fmj = FusedJarEntry(
            "fabric.mod.json", """{"id": "depmod"}""".toByteArray())
        assertSame(fmj, s.visitEntry(fmj, ctx()))
    }

}
