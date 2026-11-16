package xyz.wagyourtail.unimined.remapper.jst

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
import xyz.wagyourtail.unimined.api.spec.RemapSpec
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

/**
 * `jst` step execution against a fake `net.neoforged.jst.cli.Main`
 * compiled in-test — it writes the output archive itself (input
 * entries + an `argv.txt` echo), so the produced jar proves the
 * argv/`--enable`/`--classpath` plumbing, the unmountable-classpath
 * filtering, and the non-zero-exit failure path end-to-end.
 */
class JstExecTest {

    @TempDir
    lateinit var dir: Path

    private val objects = ProjectBuilder.builder().build().objects
    private val type = JstStepType()

    /** Fake jst-cli: last two argv are input/output archives; copies
     *  input entries into the output zip plus `argv.txt`, and honors
     *  `--fail` for the error path. */
    private val toolSource = """
        package net.neoforged.jst.cli;
        import java.io.IOException;
        import java.io.InputStream;
        import java.nio.file.Files;
        import java.nio.file.Path;
        import java.nio.file.Paths;
        import java.util.jar.JarEntry;
        import java.util.jar.JarOutputStream;
        import java.util.zip.ZipFile;
        public class Main {
            public static void main(String[] args) throws IOException {
                for (String a : args) if (a.equals("--fail")) System.exit(7);
                Path in = Paths.get(args[args.length - 2]);
                Path out = Paths.get(args[args.length - 1]);
                try (ZipFile zin = new ZipFile(in.toFile());
                     JarOutputStream jos = new JarOutputStream(Files.newOutputStream(out))) {
                    java.util.Enumeration<? extends java.util.zip.ZipEntry> e = zin.entries();
                    while (e.hasMoreElements()) {
                        java.util.zip.ZipEntry en = e.nextElement();
                        if (en.isDirectory()) continue;
                        jos.putNextEntry(new JarEntry(en.getName()));
                        try (InputStream is = zin.getInputStream(en)) {
                            is.transferTo(jos);
                        }
                        jos.closeEntry();
                    }
                    jos.putNextEntry(new JarEntry("argv.txt"));
                    jos.write(String.join("\n", args).getBytes("UTF-8"));
                    jos.closeEntry();
                }
            }
        }
    """.trimIndent()

    private fun compileTool(): Path {
        val src = dir.resolve("tool-src/net/neoforged/jst/cli/Main.java")
            .also { it.parent.createDirectories(); Files.write(it, toolSource.toByteArray()) }
        val classes = dir.resolve("tool-classes")
        val rc = ToolProvider.getSystemJavaCompiler().run(
            null, null, null,
            "-d", classes.toString(), src.toString())
        check(rc == 0) { "fake jst compile failed ($rc)" }
        return classes
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
            override val mappings: MappingData? = null
            override val classpath = classpath
            // the step writes jst-out.jar directly under workDir —
            // the pipeline wiring normally pre-creates it
            override val workDir = dir.resolve("work")
                .also { Files.createDirectories(it) }
            override val logger: Logger = JavaLogWrapper(
                java.util.logging.Logger.getLogger("jst-test"))
        }

    private fun spec(toolCp: Path, enable: List<String> = emptyList(),
                     extraArgs: List<String> = emptyList()) =
        objects.newInstance(JstStepSpec::class.java).apply {
            toolClasspath.from(toolCp)
            this.enable.set(enable)
            this.extraArgs.set(extraArgs)
        }

    private fun jar(name: String, entries: Map<String, ByteArray>): Path =
        dir.resolve(name).also { jar ->
            jar.parent?.createDirectories()
            JarOutputStream(jar.outputStream()).use { out ->
                for ((entryName, bytes) in entries) {
                    out.putNextEntry(JarEntry(entryName))
                    out.write(bytes)
                    out.closeEntry()
                }
            }
        }

    private fun entries(jar: Path): Map<String, String> =
        ZipFile(jar.toFile()).use { z ->
            z.entries().toList().filter { !it.isDirectory }.associate {
                it.name to z.getInputStream(it).readBytes().toString(Charsets.UTF_8)
            }
        }

    @Test
    fun `step execs the tool jar in and jar out`() {
        val input = jar("in.jar", mapOf("A.java" to "class A {}".toByteArray()))
        val out = type.create(
            spec(compileTool(), enable = listOf("accesstransformers"),
                extraArgs = listOf("--max-queue", "2")), ctx())
            .apply(input, ctx())
        val outEntries = entries(out)
        assertEquals("class A {}", outEntries["A.java"])
        val argv = outEntries.getValue("argv.txt").lines()
        val fmtIdx = argv.indexOf("--in-format")
        assertEquals("ARCHIVE", argv[fmtIdx + 1])
        assertEquals("ARCHIVE", argv[argv.indexOf("--out-format") + 1])
        assertTrue(argv.containsAll(
            listOf("--enable", "accesstransformers", "--max-queue", "2")),
            "enable + extraArgs not forwarded: $argv")
        assertEquals(input.toString(), argv[argv.size - 2])
        assertEquals(out.toString(), argv.last())
    }

    @Test
    fun `unmountable classpath entries are dropped`() {
        val mountable = jar("libs/real.jar",
            mapOf("ok/Ok.class" to byteArrayOf(0)))
        val dirs = dir.resolve("libs/classes-dir").createDirectories()
        // Mojang's natives-only stub shape: unopenable as a zip
        val stub = dir.resolve("libs/lwjgl-platform.jar")
            .also { Files.write(it, "not-a-jar".toByteArray()) }
        val classpath = listOf(mountable, stub, dirs)
        val input = jar("in.jar", mapOf("A.java" to "x".toByteArray()))
        val c = ctx(classpath)
        val out = type.create(spec(compileTool()), c).apply(input, c)
        val argv = entries(out).getValue("argv.txt").lines()
        val cpIdx = argv.indexOf("--classpath")
        assertTrue(cpIdx > 0, "--classpath flag missing: $argv")
        val cp = argv[cpIdx + 1]
        assertTrue(mountable.toString() in cp)
        assertTrue(dirs.toString() in cp)
        assertTrue(stub.toString() !in cp,
            "unmountable stub must be filtered: $cp")
    }

    @Test
    fun `no classpath flag when every entry is unmountable or absent`() {
        val stub = dir.resolve("stub.jar")
            .also { Files.write(it, byteArrayOf(1)) }
        val c = ctx(listOf(stub))
        val out = type.create(spec(compileTool()), c)
            .apply(jar("in.jar", mapOf("A.java" to "x".toByteArray())), c)
        val argv = entries(out).getValue("argv.txt").lines()
        assertTrue("--classpath" !in argv,
            "empty classpath must omit the flag: $argv")
    }

    @Test
    fun `non-zero tool exit fails the step with the tool output`() {
        val input = jar("in.jar", mapOf("A.java" to "x".toByteArray()))
        val e = assertThrows<GradleException> {
            type.create(spec(compileTool(), extraArgs = listOf("--fail")), ctx())
                .apply(input, ctx())
        }
        assertTrue(e.message!!.contains("jst tool failed"))
    }

    @Test
    fun `empty toolClasspath is a wiring error`() {
        val bare = objects.newInstance(JstStepSpec::class.java)
            .apply { enable.set(emptyList()); extraArgs.set(emptyList()) }
        val e = assertThrows<IllegalArgumentException> {
            type.create(bare, ctx())
                .apply(jar("in.jar", mapOf("A.java" to "x".toByteArray())), ctx())
        }
        assertTrue(e.message!!.contains("toolClasspath is empty"))
    }

}
