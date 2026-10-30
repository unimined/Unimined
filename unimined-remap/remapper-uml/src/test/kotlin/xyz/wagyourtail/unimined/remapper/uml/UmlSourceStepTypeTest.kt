package xyz.wagyourtail.unimined.remapper.uml

import xyz.wagyourtail.unimined.remap.spi.PipelineState
import kotlinx.coroutines.runBlocking
import org.gradle.api.file.FileSystemLocation
import org.gradle.api.logging.Logger
import org.gradle.api.logging.Logging
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import xyz.wagyourtail.unimined.api.mapping.MappingData
import xyz.wagyourtail.unimined.api.mapping.UmlMappingData
import xyz.wagyourtail.unimined.api.spec.RemapSpec
import xyz.wagyourtail.unimined.mapping.MutableMappingTree
import xyz.wagyourtail.unimined.mapping.formats.tiny.v2.TinyV2Reader
import xyz.wagyourtail.unimined.mapping.mappingTree
import xyz.wagyourtail.unimined.remap.RemapStepRegistry
import xyz.wagyourtail.unimined.remap.RemapStepSpec
import xyz.wagyourtail.unimined.remap.spi.RemapSurface
import xyz.wagyourtail.unimined.remap.spi.StepContext
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.tools.ToolProvider

/**
 * SOURCES-surface steps: `remap` via [UmlSourceRemapStepType] (PSI engine,
 * javadoc extension attached) and `unpick` via [UnpickSourceStepType]
 * (defs merge + classpath-resolved literal materialization).
 */
class UmlSourceStepTypeTest {

    @TempDir
    lateinit var dir: Path

    private val objects = ProjectBuilder.builder().build().objects

    private fun spec(source: String, target: String) = object : RemapSpec {
        override val sourceNamespace = source
        override val targetNamespace = target
        override val classpath: List<FileSystemLocation> = emptyList()
    }

    private fun ctx(spec: RemapSpec, tree: MutableMappingTree, classpath: List<Path>) =
        object : StepContext {
            override val state: PipelineState by lazy { PipelineState(spec.sourceNamespace, null) }
            override val spec = spec
            override val mappings: MappingData = UmlMappingData(tree)
            override val classpath = classpath
            override val workDir = dir
            override val logger: Logger = Logging.getLogger(UmlSourceStepTypeTest::class.java)
        }

    /** zip of plain `.java` entries — the sources-jar input shape. */
    private fun sourceJar(name: String, entries: Map<String, String>): Path {
        val jar = dir.resolve(name)
        ZipOutputStream(Files.newOutputStream(jar)).use { out ->
            for ((path, text) in entries) {
                out.putNextEntry(ZipEntry(path))
                out.write(text.toByteArray())
                out.closeEntry()
            }
        }
        return jar
    }

    /** compiled binary counterpart — on the step classpath for resolution. */
    private fun binJar(name: String, entries: Map<String, String>): Path {
        val srcDir = dir.resolve("src-${name.removeSuffix(".jar")}")
        val clsDir = dir.resolve("cls-${name.removeSuffix(".jar")}")
        for ((path, text) in entries) {
            val p = srcDir.resolve(path)
            Files.createDirectories(p.parent)
            Files.writeString(p, text)
        }
        Files.createDirectories(clsDir)
        val rc = ToolProvider.getSystemJavaCompiler().run(
            null, null, null,
            "-d", clsDir.toString(),
            *entries.keys.map { srcDir.resolve(it).toString() }.toTypedArray()
        )
        assertEquals(0, rc)
        val jar = dir.resolve(name)
        JarOutputStream(Files.newOutputStream(jar)).use { out ->
            Files.walk(clsDir).filter { Files.isRegularFile(it) }.forEach { f ->
                out.putNextEntry(JarEntry(clsDir.relativize(f).toString().replace('\\', '/')))
                Files.copy(f, out)
                out.closeEntry()
            }
        }
        return jar
    }

    private fun entryText(jar: Path, entry: String): String? =
        ZipFile(jar.toFile()).use { z ->
            z.getEntry(entry)?.let { z.getInputStream(it).readBytes().decodeToString() }
        }

    @Test
    fun `registry resolves uml remap for the sources surface`() {
        val type = RemapStepRegistry().resolve(
            RemapStepSpec("uml"), RemapSurface.SOURCES, spec("official", "named")
        )
        assertEquals("uml", type.name)
        assertTrue(RemapSurface.SOURCES in UmlSourceRemapStepType().surfaces)
    }

    @Test
    fun `source jar is remapped between namespaces`() {
        val tree = runBlocking {
            TinyV2Reader.read(
                """
                tiny	2	0	official	named
                c	a/A	b/B
                	f	I	f	g
                	m	()V	m	n
                """.trimIndent()
            )
        }
        val srcJar = sourceJar(
            "src-in.jar", mapOf(
                "a/A.java" to "package a; public class A { public int f; public void m() {} public void use() { this.f = 1; this.m(); } }"
            )
        )
        val bin = binJar(
            "bin-a.jar", mapOf(
                "a/A.java" to "package a; public class A { public int f; public void m() {} }"
            )
        )

        val c = ctx(spec("official", "named"), tree, listOf(bin))
        val out = UmlSourceRemapStepType().create(RemapStepSpec("uml"), c).apply(srcJar, c)

        // package dir follows the class mapping; the filename keeps the
        // entry's own name (path is jar layout, not symbol)
        val text = ZipFile(out.toFile()).use { z ->
            z.entries().asSequence().firstOrNull { it.name.endsWith(".java") }
                ?.let { it.name to z.getInputStream(it).readBytes().decodeToString() }
        }
        assertNotNull(text)
        assertTrue(text!!.first.startsWith("b/"), "mapped package dir: ${text.first}")
        assertTrue(text.second.contains("class B"), "remapped source: ${text.second}")
        assertTrue(text.second.contains("this.g"), "field renamed: ${text.second}")
        assertTrue(text.second.contains("this.n()"), "method renamed: ${text.second}")
    }

    @Test
    fun `unpick replaces literals via defs and classpath constants`() {
        val tree = mappingTree("named")

        val sources = mapOf(
            "d/Dir.java" to "package d; public class Dir { public static final int NORTH = 0; public static final int SOUTH = 1; }",
            "a/Use.java" to "package a; public class Use { void setDir(int dir) {} void go() { setDir(0); setDir(1); int x = 5; } }",
        )
        val srcJar = sourceJar("src-unpick.jar", sources)
        val bin = binJar("bin-unpick.jar", sources)

        val defs = dir.resolve("defs.unpick")
        Files.writeString(
            defs, """
            v2
            constant dir d/Dir NORTH
            constant dir d/Dir SOUTH
            target_method a/Use setDir (I)V
                param 0 dir
        """.trimIndent()
        )

        val stepSpec = objects.newInstance(UnpickSourceStepSpec::class.java)
        stepSpec.defs.from(defs)

        val c = ctx(spec("named", "named"), tree, listOf(bin))
        val out = UnpickSourceStepType().create(stepSpec, c).apply(srcJar, c)

        val text = entryText(out, "a/Use.java")
        assertNotNull(text)
        assertTrue(
            text!!.contains("setDir(d.Dir.NORTH)") && text.contains("setDir(d.Dir.SOUTH)"),
            "unpicked: $text"
        )
        assertTrue(text.contains("int x = 5;"), "non-target literal untouched: $text")
    }

}
