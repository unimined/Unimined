package xyz.wagyourtail.unimined.transform.forge.internal

import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.InsnNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.MethodNode
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
import xyz.wagyourtail.unimined.transform.forge.internal.binpatch.BinpatchesPackPatcher
import xyz.wagyourtail.unimined.transform.forge.internal.binpatch.BinpatchesPackStepSpec
import xyz.wagyourtail.unimined.transform.forge.internal.binpatch.BinpatchesPackStepType
import xyz.wagyourtail.unimined.transform.forge.internal.fg2.FG2DevFixesStepSpec
import xyz.wagyourtail.unimined.transform.forge.internal.fg2.FG2DevFixesStepType
import xyz.wagyourtail.unimined.transform.forge.internal.userdev.UserdevBinpatchStepSpec
import xyz.wagyourtail.unimined.transform.forge.internal.userdev.UserdevBinpatchStepType
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarInputStream
import java.util.jar.JarOutputStream
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.apache.commons.compress.compressors.lzma.LZMACompressorOutputStream
import org.apache.commons.compress.harmony.pack200.Pack200PackerAdapter
import kotlin.io.path.outputStream
import kotlin.io.path.writeBytes

/**
 * The forge JAR-surface step types — [StringClassNamesStepType],
 * [BinpatchesPackStepType], [UserdevBinpatchStepType],
 * [FG2DevFixesStepType] — executed through [StepContext] fakes over
 * synthetic jars/patch packs (the pack is a real lzma→pack200→jar
 * container built in-test, and patch bytes are real GDiff deltas).
 */
class ForgeStepTypesTest {

    @TempDir
    lateinit var dir: Path

    private val objects = ProjectBuilder.builder().build().objects

    private val official = Namespace("official")
    private val named = Namespace("named")

    private fun ctx(
        tree: MutableMappingTree? = null,
        classpath: List<Path> = emptyList()
    ): StepContext = object : StepContext {
        override val surface = RemapSurface.JAR
        override val state: PipelineState by lazy {
            PipelineState(spec.sourceNamespace, null)
        }
        override val spec = object : RemapSpec {
            override val sourceNamespace = "official"
            override val targetNamespace = "named"
            override val classpath: List<Path> = emptyList()
        }
        override val mappings: MappingData? =
            tree?.let { ViewMapMappingData(mapOf(MappingTree::class to it)) }
        override val classpath = classpath
        // string-classnames uses createTempDirectory(workDir) —
        // the parent must exist
        override val workDir = dir.resolve("work-${counter++}")
            .also { java.nio.file.Files.createDirectories(it) }
        override val logger: Logger = JavaLogWrapper(
            java.util.logging.Logger.getLogger("forge-steps-test"))
    }

    private var counter = 0

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

    private fun shellClass(name: String): ClassNode = ClassNode().apply {
        version = Opcodes.V1_8
        access = Opcodes.ACC_PUBLIC
        this.name = name
        superName = "java/lang/Object"
    }

    private fun writeClass(cn: ClassNode): ByteArray =
        ClassWriter(0).also { cn.accept(it) }.toByteArray()

    private fun ldcClass(name: String, values: List<Any>): ByteArray {
        val cn = shellClass(name)
        cn.methods.add(MethodNode(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC,
            "strings", "()V", null, null).apply {
            values.forEach { instructions.add(LdcInsnNode(it)) }
            instructions.add(InsnNode(Opcodes.RETURN))
        })
        return writeClass(cn)
    }

    private fun ldcStrings(jar: Path, internal: String): List<Any?> {
        val cn = ClassNode()
        ZipFile(jar.toFile()).use { z ->
            ClassReader(z.getInputStream(z.getEntry("$internal.class")))
                .accept(cn, 0)
        }
        return cn.methods.single { it.name == "strings" }.instructions
            .toArray().filterIsInstance<LdcInsnNode>().map { it.cst }
    }

    private fun tree(vararg pairs: Pair<String, String>): MutableMappingTree =
        MutableMappingTree(setOf(official, named)).apply {
            for ((src, dst) in pairs) {
                clazz(mapOf(
                    official to InternalName.unchecked(src),
                    named to InternalName.unchecked(dst)))
            }
        }

    // ---------- StringClassNamesStepType ----------

    private fun stringSpec(enabled: Boolean, classes: List<String>) =
        objects.newInstance(StringClassNamesStepSpec::class.java).apply {
            this.enabled.set(enabled)
            this.classes.set(classes)
        }

    private val holder = "forge/reg/ObjectHolderRegistry"

    private fun stringJar() = jar("dev.jar", mapOf(
        "$holder.class" to ldcClass(holder, listOf(
            "La/Obf;",           // desc literal
            "a.Obf",             // dotty literal
            "a/Obf",             // slash literal
            "mixed.a/Obf",       // mixed — not a class name
            "unmapped/Name",     // unmapped — unchanged
            42,                  // non-string LDC — passes through
        )),
        "unrelated/Un.class" to ldcClass("unrelated/Un",
            listOf("La/Obf;"))))

    @Test
    fun `string-classnames remaps desc slash and dotty literals`() {
        val c = ctx(tree("a/Obf" to "com/example/Named"))
        val out = StringClassNamesStepType()
            .create(stringSpec(true, listOf(holder)), c)
            .apply(stringJar(), c)
        val l = ldcStrings(out, holder)
        assertEquals("Lcom/example/Named;", l[0])
        assertEquals("com.example.Named", l[1])
        assertEquals("com/example/Named", l[2])
        assertEquals("mixed.a/Obf", l[3], "mixed separators stay")
        assertEquals("unmapped/Name", l[4], "unmapped names stay")
        assertEquals(42, l[5])
        // classes outside `classes` are not visited
        assertEquals("La/Obf;", ldcStrings(out, "unrelated/Un")[0])
    }

    @Test
    fun `string-classnames disabled is a passthrough`() {
        val input = stringJar()
        val c = ctx(tree("a/Obf" to "com/example/Named"))
        val out = StringClassNamesStepType()
            .create(stringSpec(false, listOf(holder)), c)
            .apply(input, c)
        assertSame(input, out)
    }

    @Test
    fun `string-classnames without a mapping tree is a passthrough`() {
        val input = stringJar()
        val c = ctx(null)
        val out = StringClassNamesStepType()
            .create(stringSpec(true, listOf(holder)), c)
            .apply(input, c)
        assertSame(input, out)
    }

    @Test
    fun `string-classnames missing scoped class skips cleanly`() {
        val c = ctx(tree("a/Obf" to "com/example/Named"))
        val out = StringClassNamesStepType()
            .create(stringSpec(true, listOf("absent/Holder")), c)
            .apply(stringJar(), c)
        // scoped name absent — jar copied, nothing rewritten
        assertEquals("La/Obf;", ldcStrings(out, holder)[0])
    }

    // ---------- pack/zip patch fixtures ----------

    private fun packRecord(
        name: String, src: String, tgt: String,
        exists: Boolean, checksum: Int, patch: ByteArray
    ): ByteArray = ByteArrayOutputStream().also { bos ->
        DataOutputStream(bos).use { w ->
            w.writeUTF(name); w.writeUTF(src); w.writeUTF(tgt)
            w.writeBoolean(exists)
            if (exists) w.writeInt(checksum)
            w.writeInt(patch.size); w.write(patch)
        }
    }.toByteArray()

    private fun zipRecord(
        obf: String, name: String,
        exists: Boolean, checksum: Int, patch: ByteArray
    ): ByteArray = ByteArrayOutputStream().also { bos ->
        DataOutputStream(bos).use { w ->
            w.writeByte(0); w.writeUTF(obf); w.writeUTF(name)
            w.writeBoolean(exists)
            if (exists) w.writeInt(checksum)
            w.writeInt(patch.size); w.write(patch)
        }
    }.toByteArray()

    private fun gdiff(src: ByteArray, tgt: ByteArray): ByteArray =
        com.nothome.delta.Delta().compute(src, tgt)

    private fun adler(b: ByteArray) = BinpatchesPackPatcher.adlerHash(b)

    /** lzma(pack200(jar)) — the FG2-era container shape. The inner
     *  jar entries must be STORED: the harmony pack200 packer reads
     *  entry content via `JarEntry.getSize()`, which is -1 for
     *  DEFLATED entries streamed through `JarInputStream` — deflated
     *  fixture entries would silently pack as 0-byte files. */
    private fun packLzma(jarEntries: Map<String, ByteArray>): ByteArray {
        val jarBytes = ByteArrayOutputStream().also { bos ->
            JarOutputStream(bos).use { jos ->
                for ((n, b) in jarEntries) {
                    val e = JarEntry(n).apply {
                        method = java.util.zip.ZipEntry.STORED
                        size = b.size.toLong()
                        compressedSize = b.size.toLong()
                        crc = java.util.zip.CRC32()
                            .also { it.update(b) }.value
                    }
                    jos.putNextEntry(e); jos.write(b); jos.closeEntry()
                }
            }
        }.toByteArray()
        val pack200 = ByteArrayOutputStream().also { bos ->
            Pack200PackerAdapter().pack(
                JarInputStream(ByteArrayInputStream(jarBytes)), bos)
        }.toByteArray()
        return ByteArrayOutputStream().also { bos ->
            LZMACompressorOutputStream(bos).use { it.write(pack200) }
        }.toByteArray()
    }

    private fun zipLzma(entries: Map<String, ByteArray>): ByteArray =
        ByteArrayOutputStream().also { bos ->
            LZMACompressorOutputStream(bos).use { lz ->
                ZipOutputStream(lz).use { zos ->
                    for ((n, b) in entries) {
                        zos.putNextEntry(java.util.zip.ZipEntry(n))
                        zos.write(b); zos.closeEntry()
                    }
                }
            }
        }.toByteArray()

    // ---------- BinpatchesPackStepType ----------

    private val orig = "orig-bytes".toByteArray()
    private val patched = "patched!!".toByteArray()

    /** universal jar carrying `binpatches.pack.lzma` with one
     *  client modify patch (`nz` orig→patched) and one client add
     *  patch (`added/New`). */
    private fun universalJar(): Path {
        val modRec = packRecord("nz patch", "nz", "nz",
            exists = true, checksum = adler(orig), patch = gdiff(orig, patched))
        val addRec = packRecord("new patch", "added/New", "added/New",
            exists = false, checksum = 0,
            patch = gdiff(ByteArray(0), "added-body".toByteArray()))
        return jar("universal.jar", mapOf(
            "binpatches.pack.lzma" to packLzma(mapOf(
                "binpatch/client/nz.binpatch" to modRec,
                "binpatch/client/new.binpatch" to addRec))))
    }

    private fun packSpec(
        loader: Path, side: String = "client",
        clientJar: Path? = null
    ) = objects.newInstance(BinpatchesPackStepSpec::class.java).apply {
        loaderJar.fileValue(loader.toFile())
        packEntry.set("binpatches.pack.lzma")
        this.side.set(side)
        clientJar?.let { this.clientJar.fileValue(it.toFile()) }
    }

    @Test
    fun `binpatches-pack step patches and adds classes`() {
        val input = jar("mc.jar", mapOf(
            "nz.class" to orig,
            "keep/K.class" to "k".toByteArray()))
        val tree = tree()
        val c = ctx(tree)
        val out = BinpatchesPackStepType()
            .create(packSpec(universalJar()), c).apply(input, c)
        val e = entries(out)
        assertArrayEquals(patched, e["nz.class"])
        assertArrayEquals("added-body".toByteArray(), e["added/New.class"])
        assertEquals("k", e["keep/K.class"]!!.decodeToString())
        // output is the canonical workDir artifact
        assertTrue(out.endsWith("forge-binpatch-pack/patched.jar"))
    }

    @Test
    fun `binpatches-pack added inner classes get mapping rows`() {
        val inner = "added/New\$Inner".toByteArray()
        val innerRec = packRecord("inner", "added/New\$Inner",
            "added/New\$Inner", exists = false, checksum = 0,
            patch = gdiff(ByteArray(0), inner))
        val universal = jar("universal.jar", mapOf(
            "binpatches.pack.lzma" to packLzma(mapOf(
                "binpatch/client/inner.binpatch" to innerRec))))
        val input = jar("mc.jar", mapOf("nz.class" to orig))
        // outer class maps so renest can derive the inner's named name
        val tree = tree("added/New" to "com/example/Added")
        val c = ctx(tree)
        BinpatchesPackStepType()
            .create(packSpec(universal), c).apply(input, c)
        val row = tree.getClass(official,
            InternalName.unchecked("added/New\$Inner"))
        assertNotNull(row, "patched-in inner class must be renested")
        assertEquals("com/example/Added\$Inner",
            row!!.names[named]?.value)
    }

    @Test
    fun `binpatches-pack absent spec fields pass through`() {
        val input = jar("mc.jar", mapOf("nz.class" to orig))
        val bare = objects.newInstance(BinpatchesPackStepSpec::class.java)
        val c = ctx(tree())
        val out = BinpatchesPackStepType().create(bare, c).apply(input, c)
        assertSame(input, out)
    }

    // ---------- UserdevBinpatchStepType ----------

    /** installer jar carrying `data/joined.lzma` — zip-era records. */
    private fun installerJar(): Path {
        val modRec = zipRecord("nz", "nz",
            exists = true, checksum = adler(orig), patch = gdiff(orig, patched))
        return jar("installer.jar", mapOf(
            "data/joined.lzma" to zipLzma(
                mapOf("nz.binpatch" to modRec))))
    }

    private fun userdevSpec(
        installer: Path, key: String = "data/joined.lzma",
        clean: Path? = null
    ) = objects.newInstance(UserdevBinpatchStepSpec::class.java).apply {
        installerJar.fileValue(installer.toFile())
        binpatchesKey.set(key)
        clean?.let { cleanJar.fileValue(it.toFile()) }
    }

    @Test
    fun `userdev-binpatch patches cleanJar not the pipeline input`() {
        val clean = jar("clean.jar", mapOf("nz.class" to orig))
        // the pipeline input differs from clean — patching happens
        // against `clean` per FG semantics
        val input = jar("input.jar", mapOf("nz.class" to "other".toByteArray()))
        val c = ctx(tree())
        val out = UserdevBinpatchStepType()
            .create(userdevSpec(installerJar(), clean = clean), c)
            .apply(input, c)
        assertArrayEquals(patched, entries(out)["nz.class"])
        assertTrue(out.endsWith("forge-binpatch/patched.jar"))
    }

    @Test
    fun `userdev-binpatch without cleanJar patches the input`() {
        val input = jar("mc.jar", mapOf("nz.class" to orig))
        val c = ctx(tree())
        val out = UserdevBinpatchStepType()
            .create(userdevSpec(installerJar()), c).apply(input, c)
        assertArrayEquals(patched, entries(out)["nz.class"])
    }

    @Test
    fun `userdev-binpatch absent spec fields pass through`() {
        val input = jar("mc.jar", mapOf("nz.class" to orig))
        val bare = objects.newInstance(UserdevBinpatchStepSpec::class.java)
        val c = ctx(tree())
        assertSame(input,
            UserdevBinpatchStepType().create(bare, c).apply(input, c))
    }

    @Test
    fun `userdev-binpatch missing archive key errors`() {
        val input = jar("mc.jar", mapOf("nz.class" to orig))
        val c = ctx(tree())
        val e = org.junit.jupiter.api.assertThrows<org.gradle.api.GradleException> {
            UserdevBinpatchStepType()
                .create(userdevSpec(installerJar(), "data/absent.lzma"), c)
                .apply(input, c)
        }
        assertTrue(e.message!!.contains("no binpatches entry"))
    }

    /** userdev jar carrying a NeoForge `patches.lzma` bundle: a JOINED
     *  `nz` modify patch, a JOINED `META-INF/neoforge.mods.toml`
     *  create, and a CLIENT-only create. */
    private fun bundleUserdevJar(): Path {
        val joined = BinpatchesPackPatcher.PatchBase.JOINED.mask
        val client = BinpatchesPackPatcher.PatchBase.CLIENT.mask
        val create = BinpatchesPackPatcher.BundlePatch.Operation.CREATE.bits
        val modify = BinpatchesPackPatcher.BundlePatch.Operation.MODIFY.bits
        fun entry(w: DataOutputStream, flags: Int, path: String, data: ByteArray, checksum: Int? = null) {
            w.writeByte(flags)
            w.writeShort(path.length); w.write(path.toByteArray())
            checksum?.let(w::writeInt)
            w.writeInt(data.size); w.write(data)
        }
        val bundle = ByteArrayOutputStream().also { bos ->
            DataOutputStream(bos).use { w ->
                w.write("NFPATCHBUNDLE001".toByteArray())
                w.writeInt(3)
                w.writeByte(client or joined)
                entry(w, modify or joined, "nz.class", gdiff(orig, patched), adler(orig))
                entry(w, create or joined, "META-INF/neoforge.mods.toml",
                    "modId=\"minecraft\"".toByteArray())
                entry(w, create or client, "client/Only.class", "client".toByteArray())
            }
        }.toByteArray()
        return jar("userdev.jar", mapOf(
            "patches.lzma" to ByteArrayOutputStream().also { bos ->
                LZMACompressorOutputStream(bos).use { it.write(bundle) }
            }.toByteArray()))
    }

    @Test
    fun `userdev-binpatch applies a patch bundle for its base`() {
        val input = jar("mc.jar", mapOf("nz.class" to orig))
        val c = ctx(tree())
        val spec = userdevSpec(bundleUserdevJar(), "patches.lzma").apply {
            baseType.set(BinpatchesPackPatcher.PatchBase.JOINED)
        }
        val e = entries(UserdevBinpatchStepType().create(spec, c).apply(input, c))
        assertArrayEquals(patched, e["nz.class"])
        assertEquals("modId=\"minecraft\"", e["META-INF/neoforge.mods.toml"]!!.decodeToString(),
            "created META-INF entries are written verbatim")
        assertTrue("client/Only.class" !in e, "entries of another base are not applied")
    }

    @Test
    fun `userdev-binpatch patch bundle without a base errors`() {
        val input = jar("mc.jar", mapOf("nz.class" to orig))
        val c = ctx(tree())
        val e = org.junit.jupiter.api.assertThrows<org.gradle.api.GradleException> {
            UserdevBinpatchStepType()
                .create(userdevSpec(bundleUserdevJar(), "patches.lzma"), c)
                .apply(input, c)
        }
        assertTrue(e.message!!.contains("patches.lzma"), e.message)
    }

    // ---------- FG2DevFixesStepType ----------

    private fun fg2Spec(enabled: Boolean) =
        objects.newInstance(FG2DevFixesStepSpec::class.java)
            .apply { this.enabled.set(enabled) }

    @Test
    fun `fg2-fixes splices the runtime helper and keeps entries`() {
        val input = jar("mc.jar", mapOf(
            "nz.class" to writeClass(shellClass("nz"))))
        val c = ctx()
        val out = FG2DevFixesStepType()
            .create(fg2Spec(true), c).apply(input, c)
        val e = entries(out)
        assertTrue(e.containsKey(
            "xyz/wagyourtail/unimined/transform/forge/internal/fg2/rt/UniminedFg2.class"),
            "the UniminedFg2 helper must be spliced in: ${e.keys}")
        assertTrue(e.containsKey("nz.class"))
        assertTrue(out.endsWith("forge-fg2-fixes/fixed.jar"))
        // input is never mutated in place
        assertNull(entries(input)[
            "xyz/wagyourtail/unimined/transform/forge/internal/fg2/rt/UniminedFg2.class"])
    }

    @Test
    fun `fg2-fixes disabled is a passthrough`() {
        val input = jar("mc.jar", mapOf("nz.class" to byteArrayOf(0)))
        val c = ctx()
        assertSame(input, FG2DevFixesStepType()
            .create(fg2Spec(false), c).apply(input, c))
    }

}
