package xyz.wagyourtail.unimined.transform.conversion.internal

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassReader
import org.objectweb.asm.tree.ClassNode
import xyz.wagyourtail.commons.core.logger.JavaLogWrapper
import xyz.wagyourtail.unimined.api.mapping.MappingData
import xyz.wagyourtail.unimined.transform.processor.JarProcessor
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.zip.ZipFile
import javax.tools.ToolProvider
import kotlin.io.path.createDirectories
import kotlin.io.path.outputStream

/**
 * The conversion `jar-processors` pair — BTA's vestigial `a` stub
 * probe and FoxLoader's superset-patch strip-back — over synthetic
 * jars. The FoxLoader invoker is a fake
 * `com.fox2code.foxloader.invoker.Main` compiled in-test: it echoes
 * argv into the patched jar and stamps a `patched.marker` entry so
 * the strip-back pass proves the patcher actually ran.
 */
class ConversionProcessorsTest {

    @TempDir
    lateinit var dir: Path

    private val ctx = object : JarProcessor.ProcessorContext {
        override val logger = JavaLogWrapper(
            java.util.logging.Logger.getLogger("conversion-test"))
        override val mappings: MappingData? = null
        override fun mods() = emptyList<JarProcessor.ModJarInfo>()
    }

    private fun specCtx(artifacts: Map<String, List<Path>> = emptyMap()) =
        object : JarProcessor.SpecContext {
            override val productionNamespace = "official"
            override fun mods() = emptyList<JarProcessor.ModJarInfo>()
            override fun envArtifacts(name: String) =
                artifacts[name] ?: emptyList()
        }

    private fun jar(name: String, entries: Map<String, ByteArray>): Path {
        val out = dir.resolve(name)
        JarOutputStream(out.outputStream()).use { jos ->
            for ((n, b) in entries) {
                jos.putNextEntry(JarEntry(n)); jos.write(b); jos.closeEntry()
            }
        }
        return out
    }

    private fun entries(jar: Path): Map<String, ByteArray> =
        ZipFile(jar.toFile()).use { z ->
            z.entries().toList().filter { !it.isDirectory }.associate {
                it.name to z.getInputStream(it).readBytes()
            }
        }

    // ---------- BtaBetaCheckProcessor ----------

    private val marker =
        "net/minecraft/client/gui/error/ScreenMissingMinecraft.class"

    @Test
    fun `bta-beta-check adds the a stub when the probe screen exists`() {
        val target = jar("bta.jar", mapOf(
            marker to byteArrayOf(0),
            "net/minecraft/client/Minecraft.class" to byteArrayOf(1)))
        BtaBetaCheckProcessor().processJar(target,
            BtaBetaCheckProcessor.Spec(), ctx)
        val a = entries(target)["a.class"]
        assertTrue(a != null && a.size > 4,
            "stub a.class must be emitted")
        val cn = ClassNode()
        ClassReader(a).accept(cn, 0)
        assertEquals("a", cn.name)
        assertTrue(cn.access and org.objectweb.asm.Opcodes.ACC_PUBLIC != 0)
    }

    @Test
    fun `bta-beta-check no-ops on non-bta jars`() {
        val target = jar("other.jar",
            mapOf("a/A.class" to byteArrayOf(1)))
        BtaBetaCheckProcessor().processJar(target,
            BtaBetaCheckProcessor.Spec(), ctx)
        assertEquals(setOf("a/A.class"), entries(target).keys)
    }

    @Test
    fun `bta-beta-check does not overwrite an existing a class`() {
        val real = byteArrayOf(9, 9, 9)
        val target = jar("bta.jar", mapOf(
            marker to byteArrayOf(0), "a.class" to real))
        BtaBetaCheckProcessor().processJar(target,
            BtaBetaCheckProcessor.Spec(), ctx)
        assertArrayEquals(real, entries(target)["a.class"])
    }

    // ---------- FoxLoaderPatchProcessor ----------

    /** Fake invoker: `Main(in out unpick)` — copies the input zip to
     *  the output, stamps `patched.marker` + `argv.txt`, exits 4 on
     *  `--fail` in argv (unreached — kept for symmetry) or a
     *  `FAILPATCH` entry in the input. */
    private val invokerSource = """
        package com.fox2code.foxloader.invoker;
        import java.io.InputStream;
        import java.nio.file.Files;
        import java.nio.file.Path;
        import java.nio.file.Paths;
        import java.util.jar.JarEntry;
        import java.util.jar.JarOutputStream;
        import java.util.zip.ZipFile;
        public class Main {
            public static void main(String[] args) throws Exception {
                Path in = Paths.get(args[0]);
                Path out = Paths.get(args[1]);
                Files.createDirectories(out.getParent());
                try (ZipFile zin = new ZipFile(in.toFile())) {
                    if (zin.getEntry("FAILPATCH") != null) System.exit(4);
                    try (JarOutputStream jos = new JarOutputStream(
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
                        jos.putNextEntry(new JarEntry("patched.marker"));
                        jos.write(new byte[]{42});
                        jos.closeEntry();
                        jos.putNextEntry(new JarEntry("argv.txt"));
                        jos.write(String.join("\n", args)
                            .getBytes("UTF-8"));
                        jos.closeEntry();
                    }
                }
            }
        }
    """.trimIndent()

    private fun compileInvoker(): Path {
        val src = dir.resolve(
            "invoker-src/com/fox2code/foxloader/invoker/Main.java")
            .also { it.parent.createDirectories()
                Files.write(it, invokerSource.toByteArray()) }
        val classes = dir.resolve("invoker-classes")
        val rc = ToolProvider.getSystemJavaCompiler().run(
            null, null, null, "-d", classes.toString(), src.toString())
        check(rc == 0) { "fake invoker compile failed ($rc)" }
        return classes
    }

    @Test
    fun `foxloader buildSpec needs both env artifact bindings`() {
        val p = FoxLoaderPatchProcessor()
        assertNull(p.buildSpec(specCtx()))
        assertNull(p.buildSpec(specCtx(
            mapOf("foxloader:invoker" to listOf(dir)))))
        assertNull(p.buildSpec(specCtx(
            mapOf("foxloader:otherSide" to listOf(dir)))))
        val spec = p.buildSpec(specCtx(mapOf(
            "foxloader:invoker" to listOf(dir, dir),
            "foxloader:otherSide" to listOf(dir)))) as
                FoxLoaderPatchProcessor.Spec
        assertEquals(2, spec.invokerClasspath.size)
        assertEquals(dir.toAbsolutePath().toString(), spec.otherSideJar)
    }

    @Test
    fun `foxloader superset patches then strips other-side entries`() {
        val own = jar("mc.jar", mapOf(
            "a/A.class" to "own-a".toByteArray(),
            "shared/S.class" to "own-shared".toByteArray()))
        val other = jar("other.jar", mapOf(
            "other/O.class" to "other-o".toByteArray(),
            "shared/S.class" to "other-shared".toByteArray()))
        val invoker = compileInvoker()
        val p = FoxLoaderPatchProcessor()
        p.processJar(own, FoxLoaderPatchProcessor.Spec(
            listOf(invoker.toString()), other.toString()), ctx)
        val out = entries(own)
        assertEquals("own-a", out["a/A.class"]!!.decodeToString())
        // own copies win shared names through the superset
        assertEquals("own-shared", out["shared/S.class"]!!.decodeToString())
        // the other side's unique entries are stripped back out
        assertFalse(out.containsKey("other/O.class"),
            "other-side-only entries must not survive strip-back")
        // invoker inserts (names in neither jar) DO survive — that is
        // what the patch is for
        assertArrayEquals(byteArrayOf(42), out["patched.marker"])
        // the real argv: superset patched "false"
        val argv = out["argv.txt"]!!.decodeToString().lines()
        assertEquals("false", argv[2])
        assertTrue(argv[0].contains("foxloader-superset"))
        assertTrue(argv[1].contains("foxloader-patched"))
        // intermediates are cleaned up
        assertFalse(Files.exists(dir.resolve(
            "mc.jar+foxloader-superset.jar")))
        assertFalse(Files.exists(dir.resolve(
            "mc.jar+foxloader-patched.jar")))
    }

    @Test
    fun `foxloader invoker failure propagates with tool output`() {
        val own = jar("mc.jar",
            mapOf("FAILPATCH" to byteArrayOf(0)))
        val other = jar("other.jar", emptyMap())
        val p = FoxLoaderPatchProcessor()
        val e = assertThrows(IllegalStateException::class.java) {
            p.processJar(own, FoxLoaderPatchProcessor.Spec(
                listOf(compileInvoker().toString()), other.toString()), ctx)
        }
        assertTrue(e.message!!.contains("foxloader invoker failed"),
            e.message)
    }

    @Test
    fun `foxloader spec payload round-trips`() {
        val p = FoxLoaderPatchProcessor()
        val spec = FoxLoaderPatchProcessor.Spec(
            listOf("/x/a.jar", "/x/b.jar"), "/x/other.jar")
        val revived = p.deserializeSpec(
            p.serializeSpec(spec)) as FoxLoaderPatchProcessor.Spec
        assertEquals(spec.invokerClasspath, revived.invokerClasspath)
        assertEquals(spec.otherSideJar, revived.otherSideJar)
    }

}
