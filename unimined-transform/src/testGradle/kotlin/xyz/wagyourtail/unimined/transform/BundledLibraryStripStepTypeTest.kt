package xyz.wagyourtail.unimined.transform

import org.gradle.api.logging.Logging
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import xyz.wagyourtail.commons.core.logger.Logger
import xyz.wagyourtail.unimined.api.mapping.MappingData
import xyz.wagyourtail.unimined.api.spec.RemapSpec
import xyz.wagyourtail.unimined.remap.RemapStepRegistry
import xyz.wagyourtail.unimined.remap.spi.PipelineState
import xyz.wagyourtail.unimined.remap.spi.RemapSurface
import xyz.wagyourtail.unimined.remap.spi.StepContext
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import kotlin.io.path.outputStream

/**
 * The `strip-bundled-libraries` step drops artifact entries whose
 * paths exist inside the declared library jars — manifest and
 * `META-INF/services/` registrations survive, and an empty library
 * set is a passthrough.
 */
class BundledLibraryStripStepTypeTest {

    @TempDir
    lateinit var dir: Path

    private val ctx
        get() = object : StepContext {
        override val surface = RemapSurface.JAR
            override val state: PipelineState by lazy {
                PipelineState(spec.sourceNamespace, null)
            }
            override val spec = object : RemapSpec {
                override val sourceNamespace = "official"
                override val targetNamespace = "official"
                override val classpath: List<Path> = emptyList()
            }
            override val mappings: MappingData? = null
            override val classpath = emptyList<Path>()
            override val workDir = dir
            override val logger: Logger =
                xyz.wagyourtail.unimined.api.util.uniminedLogger(
                    Logging.getLogger(BundledLibraryStripStepTypeTest::class.java)
                )
        }

    private fun jar(name: String, vararg entries: String): Path {
        val jar = dir.resolve(name)
        JarOutputStream(jar.outputStream()).use { out ->
            for (e in entries) {
                out.putNextEntry(JarEntry(e))
                out.write(e.toByteArray())
                out.closeEntry()
            }
        }
        return jar
    }

    private fun spec(vararg libs: Path): BundledLibraryStripStepSpec {
        val project = ProjectBuilder.builder().build()
        return project.objects
            .newInstance(BundledLibraryStripStepSpec::class.java).apply {
                libraryJars.from(*libs.map { it.toFile() }.toTypedArray())
            }
    }

    @Test
    fun `entries matching library jars strip out and manifest and services survive`() {
        val lib = jar("lib.jar", "org/slf4j/Logger.class")
        val input = jar(
            "mc.jar",
            "META-INF/MANIFEST.MF",
            "META-INF/services/org.slf4j.spi.SLF4JServiceProvider",
            "org/slf4j/Logger.class",
            "a/A.class"
        )
        val out = BundledLibraryStripStepType()
            .create(spec(lib), ctx).apply(input, ctx)
        val names = JarFile(out.toFile()).use { j ->
            j.entries().asSequence().map { it.name }.toSet()
        }
        assertTrue("org/slf4j/Logger.class" !in names)
        assertTrue("a/A.class" in names)
        assertTrue("META-INF/MANIFEST.MF" in names)
        assertTrue(
            "META-INF/services/org.slf4j.spi.SLF4JServiceProvider" in names
        )
    }

    @Test
    fun `empty library set returns the input unchanged`() {
        val input = jar("mc.jar", "a/A.class")
        val out = BundledLibraryStripStepType()
            .create(spec(), ctx).apply(input, ctx)
        assertSame(input, out)
    }

    @Test
    fun `registry resolves the spec via ServiceLoader on JAR surface`() {
        val type = RemapStepRegistry()
            .resolve(spec(), RemapSurface.JAR, ctx.spec)
        assertEquals("strip-bundled-libraries", type.stepName)
        assertEquals("unimined", type.name)
    }
}
