package xyz.wagyourtail.unimined.transform.jarmod.internal

import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.InsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.VarInsnNode
import xyz.wagyourtail.commons.core.logger.JavaLogWrapper
import xyz.wagyourtail.commons.core.logger.Logger
import xyz.wagyourtail.unimined.api.mapping.MappingData
import xyz.wagyourtail.unimined.api.spec.RemapSpec
import xyz.wagyourtail.unimined.remap.spi.PipelineState
import xyz.wagyourtail.unimined.remap.spi.RemapSurface
import xyz.wagyourtail.unimined.remap.spi.StepContext
import xyz.wagyourtail.unimined.transform.jarmod.internal.modloader.ModLoaderDevFixesStepSpec
import xyz.wagyourtail.unimined.transform.jarmod.internal.modloader.ModLoaderDevFixesStepType
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.zip.ZipFile
import javax.tools.ToolProvider
import kotlin.io.path.createDirectories
import kotlin.io.path.outputStream

/**
 * The jarmod JAR-surface step types —
 * [JmaCompileTransformStepType] (forked jarmod-agent JVM) and
 * [ModLoaderDevFixesStepType] (in-process ModLoader dev fixes).
 * The JMA agent is a fake `xyz.wagyourtail.unimined.jarmodagent.
 * JarModAgent` compiled in-test: it echoes argv + the
 * `jma.transformers`/`jma.debug` sysprops into the output jar.
 */
class JarModStepTypesTest {

    @TempDir
    lateinit var dir: Path

    private val objects = ProjectBuilder.builder().build().objects

    private var counter = 0

    private fun ctx(classpath: List<Path> = emptyList()): StepContext =
        object : StepContext {
            override val surface = RemapSurface.JAR
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
            override val workDir = dir.resolve("work-${counter++}")
            override val logger: Logger = JavaLogWrapper(
                java.util.logging.Logger.getLogger("jarmod-steps-test"))
        }

    private fun jar(name: String, entries: Map<String, ByteArray>): Path {
        val out = dir.resolve(name)
        out.parent?.createDirectories()
        JarOutputStream(out.outputStream()).use { jos ->
            for ((n, b) in entries) {
                jos.putNextEntry(JarEntry(n)); jos.write(b); jos.closeEntry()
            }
        }
        return out
    }

    private fun entries(jar: Path): Map<String, String> =
        ZipFile(jar.toFile()).use { z ->
            z.entries().toList().filter { !it.isDirectory }.associate {
                it.name to z.getInputStream(it)
                    .readBytes().toString(Charsets.UTF_8)
            }
        }

    // ---------- JmaCompileTransformStepType ----------

    /** Fake agent: `JarModAgent staged cp out` — copies the input
     *  zip to `out`, appends `jma-report.txt` (argv + sysprops), and
     *  exits 6 when `jma.transformers` contains `fail`. */
    private val agentSource = """
        package xyz.wagyourtail.unimined.jarmodagent;
        import java.io.InputStream;
        import java.nio.file.Files;
        import java.nio.file.Path;
        import java.nio.file.Paths;
        import java.util.jar.JarEntry;
        import java.util.jar.JarOutputStream;
        import java.util.zip.ZipFile;
        public class JarModAgent {
            public static void main(String[] args) throws Exception {
                String t = System.getProperty("jma.transformers", "");
                if (t.contains("fail")) System.exit(6);
                Path in = Paths.get(args[0]);
                Path out = Paths.get(args[2]);
                Files.createDirectories(out.getParent());
                try (ZipFile zin = new ZipFile(in.toFile());
                     JarOutputStream jos = new JarOutputStream(
                         Files.newOutputStream(out))) {
                    java.util.Enumeration<? extends java.util.zip.ZipEntry>
                        e = zin.entries();
                    while (e.hasMoreElements()) {
                        java.util.zip.ZipEntry en = e.nextElement();
                        if (en.isDirectory()) continue;
                        jos.putNextEntry(new JarEntry(en.getName()));
                        try (InputStream is = zin.getInputStream(en)) {
                            is.transferTo(jos);
                        }
                        jos.closeEntry();
                    }
                    jos.putNextEntry(new JarEntry("jma-report.txt"));
                    String report = "transformers=" + t
                        + "\ndebug=" + System.getProperty("jma.debug", "")
                        + "\nargv=" + String.join("|", args);
                    jos.write(report.getBytes("UTF-8"));
                    jos.closeEntry();
                }
            }
        }
    """.trimIndent()

    private fun compileAgent(): Path {
        val src = dir.resolve(
            "agent-src/xyz/wagyourtail/unimined/jarmodagent/JarModAgent.java")
            .also { it.parent.createDirectories()
                Files.write(it, agentSource.toByteArray()) }
        val classes = dir.resolve("agent-classes")
        check(ToolProvider.getSystemJavaCompiler().run(
            null, null, null,
            "-d", classes.toString(), src.toString()) == 0)
        return classes
    }

    private fun jmaSpec(
        agent: String, transforms: List<String>, debug: Boolean = false
    ) = objects.newInstance(JmaCompileTransformStepSpec::class.java).apply {
        agentJar.set(agent)
        this.transforms.set(transforms)
        this.debug.set(debug)
    }

    private fun modJar() = jar("mod.jar",
        mapOf("mod/Mod.class" to "mod".toByteArray()))

    @Test
    fun `jma step forks the agent with staged input classpath and output`() {
        val cpEntry = jar("libs/cp.jar", mapOf("cp/Cp.class" to byteArrayOf(0)))
        val input = modJar()
        val c = ctx(classpath = listOf(cpEntry))
        val out = JmaCompileTransformStepType()
            .create(jmaSpec(compileAgent().toString(),
                listOf("tx.One", "tx.Two"), debug = true), c)
            .apply(input, c)
        val e = entries(out)
        assertEquals("mod", e["mod/Mod.class"])
        val report = e.getValue("jma-report.txt")
        assertTrue("transformers=tx.One${java.io.File.pathSeparator}tx.Two" in report,
            "transformers sysprop must join entries: $report")
        assertTrue("debug=true" in report, "jma.debug flag: $report")
        // argv: staged-in cp-joined out
        val argv = report.substringAfter("argv=").split("|")
        assertTrue(argv[0].endsWith("jma-input.jar"),
            "staged input copy: $report")
        assertTrue(cpEntry.toString() in argv[1],
            "ctx classpath must reach argv: $report")
        assertTrue(argv[2].endsWith("jma-transformed.jar"), report)
        assertTrue(out.endsWith(
            "jma-compiletime-transforms/jma-transformed.jar"))
    }

    @Test
    fun `jma step non-zero agent exit fails with the tool output`() {
        val input = modJar()
        val c = ctx()
        val e = assertThrows<GradleException> {
            JmaCompileTransformStepType()
                .create(jmaSpec(compileAgent().toString(),
                    listOf("fail.Tx")), c)
                .apply(input, c)
        }
        assertTrue(e.message!!.contains("jma-compiletime-transforms failed"),
            e.message)
        assertTrue("exit 6" in e.message!!)
    }

    @Test
    fun `jma step reuses an existing output`() {
        val input = modJar()
        val c = ctx()
        // pre-seed the canonical output — the exists-gate must skip
        // the fork entirely (no agent needed)
        val workDir = c.workDir.resolve("jma-compiletime-transforms")
        val seeded = workDir.resolve("jma-transformed.jar")
        workDir.createDirectories()
        jar("seeded-tmp.jar", mapOf("seeded.txt" to "x".toByteArray()))
            .also { Files.copy(it, seeded) }
        val out = JmaCompileTransformStepType()
            // no agent jar compiled — a real fork would crash; the
            // gate must win
            .create(jmaSpec("/nonexistent-agent.jar",
                listOf("tx")), c)
            .apply(input, c)
        assertEquals(seeded, out)
        assertEquals("x", entries(out)["seeded.txt"])
    }

    // ---------- ModLoaderDevFixesStepType ----------

    /** Minimal ModLoader: `init` calls `readFromClassPath(File)`,
     *  which calls `File.listFiles` — the 1.x patch sites. */
    private fun modLoaderClass(): ByteArray {
        val cn = ClassNode()
        cn.version = Opcodes.V1_8
        cn.access = Opcodes.ACC_PUBLIC
        cn.name = "ModLoader"
        cn.superName = "java/lang/Object"
        cn.methods.add(MethodNode(Opcodes.ACC_PUBLIC, "init",
            "()V", null, null).apply {
            instructions.add(InsnNode(Opcodes.RETURN))
        })
        cn.methods.add(MethodNode(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC,
            "readFromClassPath", "(Ljava/io/File;)V", null, null).apply {
            instructions.add(VarInsnNode(Opcodes.ALOAD, 0))
            instructions.add(MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                "java/io/File", "listFiles", "()[Ljava/io/File;", false))
            instructions.add(VarInsnNode(Opcodes.ASTORE, 1))
            instructions.add(InsnNode(Opcodes.RETURN))
        })
        return ClassWriter(ClassWriter.COMPUTE_MAXS or
                ClassWriter.COMPUTE_FRAMES).also { cn.accept(it) }.toByteArray()
    }

    private fun mlSpec(enabled: Boolean) =
        objects.newInstance(ModLoaderDevFixesStepSpec::class.java)
            .apply { this.enabled.set(enabled) }

    @Test
    fun `modloader-fixes rewrites listFiles to the helper`() {
        val input = jar("mc.jar",
            mapOf("ModLoader.class" to modLoaderClass()))
        val c = ctx()
        val out = ModLoaderDevFixesStepType()
            .create(mlSpec(true), c).apply(input, c)
        val cn = ClassNode()
        ZipFile(out.toFile()).use { z ->
            ClassReader(z.getInputStream(z.getEntry("ModLoader.class")))
                .accept(cn, 0)
        }
        assertTrue(cn.methods.any { it.name == "listAllFiles" },
            "listAllFiles helper must be copied onto ModLoader")
        assertTrue(out.endsWith("jarmod-modloader-fixes/fixed.jar"))
    }

    @Test
    fun `modloader-fixes without ModLoader still produces output`() {
        val input = jar("mc.jar",
            mapOf("keep/K.class" to "k".toByteArray()))
        val c = ctx()
        val out = ModLoaderDevFixesStepType()
            .create(mlSpec(true), c).apply(input, c)
        assertEquals("k", entries(out)["keep/K.class"])
    }

    @Test
    fun `modloader-fixes disabled is a passthrough`() {
        val input = jar("mc.jar",
            mapOf("ModLoader.class" to modLoaderClass()))
        val c = ctx()
        assertSame(input, ModLoaderDevFixesStepType()
            .create(mlSpec(false), c).apply(input, c))
    }

}
