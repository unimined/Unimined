package xyz.wagyourtail.unimined.remapper.source

import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import xyz.wagyourtail.commons.core.logger.JavaLogWrapper
import xyz.wagyourtail.commons.core.logger.Logger
import xyz.wagyourtail.unimined.api.mapping.MappingData
import xyz.wagyourtail.unimined.api.mapping.ViewMapMappingData
import xyz.wagyourtail.unimined.api.spec.RemapSpec
import xyz.wagyourtail.unimined.mapping.MappingTree
import xyz.wagyourtail.unimined.mapping.MutableMappingTree
import xyz.wagyourtail.unimined.mapping.Namespace
import xyz.wagyourtail.unimined.mapping.jvms.four.two.one.InternalName
import xyz.wagyourtail.unimined.remap.spi.PipelineState
import xyz.wagyourtail.unimined.remap.spi.RemapSurface
import xyz.wagyourtail.unimined.remap.spi.StepContext
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.zip.ZipFile
import javax.tools.ToolProvider
import kotlin.io.path.createDirectories
import kotlin.io.path.outputStream
import kotlin.io.path.readText

/**
 * `sourceRemap` step execution against a fake tool — a tiny
 * `com.replaymod.gradle.remap.MainKt` compiled in-test stands in for
 * the real source-remap jar: it copies the unpacked source tree to
 * the out dir and echoes its argv back through `argv.txt`, so the
 * repacked jar itself proves the arg plumbing. The real `-cp`
 * classpath wiring, `-r` in/out dirs, `-m` SRG file, `extraArgs`
 * forwarding, and non-zero-exit failure mapping are covered.
 */
class SourceRemapExecTest {

    @TempDir
    lateinit var dir: Path

    private val objects = ProjectBuilder.builder().build().objects
    private val type = SourceRemapStepType()

    /** Fake tool main — mirrors the real CLI's `-r in out` contract
     *  and reports its argv through a file that lands in the repacked
     *  jar. `--fail` exits non-zero to cover the failure path. */
    private val toolSource = """
        package com.replaymod.gradle.remap;
        import java.io.IOException;
        import java.io.UncheckedIOException;
        import java.nio.file.Files;
        import java.nio.file.Path;
        import java.nio.file.Paths;
        public class MainKt {
            public static void main(String[] args) throws IOException {
                Path in = null, out = null;
                for (int i = 0; i < args.length; i++) {
                    if (args[i].equals("-r")) {
                        in = Paths.get(args[i + 1]);
                        out = Paths.get(args[i + 2]);
                    }
                    if (args[i].equals("--fail")) System.exit(3);
                }
                Path inF = in, outF = out;
                Files.walk(inF).filter(Files::isRegularFile).forEach(p -> {
                    try {
                        Path t = outF.resolve(inF.relativize(p).toString());
                        if (t.getParent() != null) Files.createDirectories(t.getParent());
                        Files.copy(p, t);
                    } catch (IOException e) { throw new UncheckedIOException(e); }
                });
                Files.write(outF.resolve("argv.txt"),
                    String.join("\n", args).getBytes("UTF-8"));
            }
        }
    """.trimIndent()

    private fun compileTool(): Path {
        val src = dir.resolve("tool-src/com/replaymod/gradle/remap/MainKt.java")
            .also { it.parent.createDirectories(); Files.write(it, toolSource.toByteArray()) }
        val classes = dir.resolve("tool-classes")
        val rc = ToolProvider.getSystemJavaCompiler().run(
            null, null, null,
            "-d", classes.toString(), src.toString())
        check(rc == 0) { "fake tool compile failed ($rc)" }
        return classes
    }

    private val official = Namespace("official")
    private val named = Namespace("named")

    /** `a/Obf` → `com/example/Named` — enough for the SRG `CL:` row. */
    private fun tree(): MutableMappingTree =
        MutableMappingTree(setOf(official, named)).apply {
            clazz(mapOf(
                official to InternalName.unchecked("a/Obf"),
                named to InternalName.unchecked("com/example/Named")))
        }

    private fun ctx(classpath: List<Path> = emptyList()): StepContext =
        object : StepContext {
            override val surface = RemapSurface.SOURCES
            override val state: PipelineState by lazy {
                PipelineState(spec.sourceNamespace, null)
            }
            override val spec = object : RemapSpec {
                override val sourceNamespace = "official"
                override val targetNamespace = "named"
                override val classpath: List<Path> = emptyList()
            }
            override val mappings: MappingData =
                ViewMapMappingData(mapOf(MappingTree::class to tree()))
            override val classpath = classpath
            override val workDir = dir.resolve("work")
            override val logger: Logger = JavaLogWrapper(
                java.util.logging.Logger.getLogger("sourceRemap-test"))
        }

    private fun spec(toolCp: Path, extraArgs: List<String> = emptyList(),
                     extraCp: List<Path> = emptyList()) =
        objects.newInstance(SourceRemapStepSpec::class.java).apply {
            toolClasspath.from(toolCp)
            this.extraClasspath.from(extraCp)
            this.extraArgs.set(extraArgs)
        }

    private fun sourcesJar() = dir.resolve("in.jar").also { jar ->
        JarOutputStream(jar.outputStream()).use { out ->
            out.putNextEntry(JarEntry("com/example/Foo.java"))
            out.write("class Foo {}".toByteArray())
            out.closeEntry()
        }
    }

    private fun entries(jar: Path): Map<String, String> =
        ZipFile(jar.toFile()).use { z ->
            z.entries().toList().filter { !it.isDirectory }.associate {
                it.name to z.getInputStream(it).readBytes().toString(Charsets.UTF_8)
            }
        }

    @Test
    fun `step unpacks execs the tool and repacks its output`() {
        val out = type.create(spec(compileTool()), ctx()).apply(sourcesJar(), ctx())
        val outEntries = entries(out)
        assertEquals("class Foo {}", outEntries["com/example/Foo.java"])
        val argv = outEntries.getValue("argv.txt").lines()
        // main args: -cp <ctx cp> -r <in> <out> -m <srg>
        assertEquals("-cp", argv[0])
        val rIdx = argv.indexOf("-r")
        assertTrue(rIdx > 0, "-r flag missing: $argv")
        assertEquals("-m", argv[rIdx + 3])
        assertTrue(Files.isDirectory(Path.of(argv[rIdx + 1])))
        assertTrue(argv[rIdx + 1].endsWith("src-in"))
        assertTrue(argv[rIdx + 2].endsWith("src-out"))
        // the SRG handed to the tool carries the class row
        val srg = Path.of(argv[rIdx + 4]).readText()
        assertTrue(srg.contains("CL: a/Obf com/example/Named"), srg)
    }

    @Test
    fun `ctx classpath extraClasspath and extraArgs reach the tool`() {
        val cpEntry = dir.resolve("libs/mc.jar").toFile()
            .apply { parentFile.mkdirs(); writeBytes(byteArrayOf(0)) }
            .toPath()
        val extraCp = dir.resolve("libs/extra.jar").toFile()
            .apply { writeBytes(byteArrayOf(1)) }.toPath()
        val missing = dir.resolve("libs/absent.jar") // filtered: !exists
        val out = type.create(
            spec(compileTool(), extraArgs = listOf("--no-srg-in-attr"),
                extraCp = listOf(extraCp)),
            ctx(classpath = listOf(cpEntry, missing)))
            .apply(sourcesJar(), ctx(classpath = listOf(cpEntry, missing)))
        val argv = entries(out).getValue("argv.txt").lines()
        val toolCpArg = argv[argv.indexOf("-cp") + 1]
        assertTrue(cpEntry.toString() in toolCpArg,
            "ctx classpath missing from tool -cp: $toolCpArg")
        assertTrue(extraCp.toString() in toolCpArg,
            "extraClasspath missing from tool -cp: $toolCpArg")
        assertTrue(missing.toString() !in toolCpArg,
            "non-existent cp entry must be filtered: $toolCpArg")
        assertTrue("--no-srg-in-attr" in argv,
            "extraArgs not forwarded: $argv")
    }

    @Test
    fun `non-zero tool exit fails the step with the tool output`() {
        val e = assertThrows<GradleException> {
            type.create(spec(compileTool(), extraArgs = listOf("--fail")), ctx())
                .apply(sourcesJar(), ctx())
        }
        assertTrue(e.message!!.contains("source-remap tool failed"))
    }

    @Test
    fun `empty toolClasspath is a wiring error`() {
        val bare = objects.newInstance(SourceRemapStepSpec::class.java)
            .apply { extraArgs.set(emptyList()) }
        val e = assertThrows<IllegalArgumentException> {
            type.create(bare, ctx()).apply(sourcesJar(), ctx())
        }
        assertTrue(e.message!!.contains("toolClasspath is empty"))
    }

}
