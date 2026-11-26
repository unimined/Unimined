package xyz.wagyourtail.unimined.transform.forge.internal.binpatch

import com.nothome.delta.Delta
import org.apache.commons.compress.compressors.lzma.LZMACompressorOutputStream
import org.apache.commons.compress.harmony.pack200.Pack200PackerAdapter
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import xyz.wagyourtail.unimined.transform.forge.internal.binpatch.BinpatchesPackPatcher.BundlePatch
import xyz.wagyourtail.unimined.transform.forge.internal.binpatch.BinpatchesPackPatcher.ClassPatch
import xyz.wagyourtail.unimined.transform.forge.internal.binpatch.BinpatchesPackPatcher.PatchBase
import xyz.wagyourtail.unimined.transform.forge.internal.binpatch.BinpatchesPackPatcher.SidePatchInput
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarInputStream
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.io.path.outputStream
import kotlin.io.path.writeBytes

/**
 * The binpatch codecs + applier behind [BinpatchesPackStepType] and
 * [UserdevBinpatchStepType] — pack-era `readPatches`, zip-era
 * `readZipPatches`, `unpackPack` (lzma→pack200→jar), and the merged
 * [BinpatchesPackPatcher.apply] pass (modify/add/discrepancy). The
 * fixture deltas are real GDiff streams from `javaxdelta`, so the
 * patch path is exercised end-to-end.
 */
class BinpatchesPackPatcherTest {

    @TempDir
    lateinit var dir: Path

    private fun gdiff(src: ByteArray, tgt: ByteArray): ByteArray =
        Delta().compute(src, tgt)

    private fun adler(bytes: ByteArray): Int =
        BinpatchesPackPatcher.adlerHash(bytes)

    /** Pack-era record: name, src dotty, tgt dotty, exists, [checksum], len, bytes. */
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

    /** Zip-era record: marker byte, obfName, name, exists, [checksum], len, bytes. */
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

    private fun jarBytes(entries: Map<String, ByteArray>): ByteArray =
        ByteArrayOutputStream().also { bos ->
            JarOutputStream(bos).use { jos ->
                for ((n, b) in entries) {
                    jos.putNextEntry(JarEntry(n)); jos.write(b); jos.closeEntry()
                }
            }
        }.toByteArray()

    private fun jar(name: String, entries: Map<String, ByteArray>): Path =
        dir.resolve(name).also { it.writeBytes(jarBytes(entries)) }

    private fun zipBytes(entries: Map<String, ByteArray>): ByteArray =
        ByteArrayOutputStream().also { bos ->
            ZipOutputStream(bos).use { zos ->
                for ((n, b) in entries) {
                    zos.putNextEntry(ZipEntry(n)); zos.write(b); zos.closeEntry()
                }
            }
        }.toByteArray()

    private fun lzma(name: String, bytes: ByteArray): Path =
        dir.resolve(name).also { p ->
            LZMACompressorOutputStream(p.outputStream()).use { it.write(bytes) }
        }

    private fun entries(jar: Path): Map<String, ByteArray> =
        ZipFile(jar.toFile()).use { z ->
            z.entries().toList().filter { !it.isDirectory }.associate {
                it.name to z.getInputStream(it).readBytes()
            }
        }

    // ---------- codecs ----------

    @Test
    fun `unpackPack lzma pack200 jar round-trips to jar entries`() {
        val jar = jarBytes(mapOf(
            "binpatch/client/nz.binpatch" to byteArrayOf(1, 2),
            "binpatch/server/zz.binpatch" to byteArrayOf(3)))
        val pack200 = ByteArrayOutputStream().also { bos ->
            Pack200PackerAdapter().pack(
                JarInputStream(ByteArrayInputStream(jar)), bos)
        }.toByteArray()
        val pack = lzma("binpatches.pack.lzma", pack200)
        val unpacked = BinpatchesPackPatcher.unpackPack(pack)
        val names = JarInputStream(ByteArrayInputStream(unpacked))
            .use { j -> generateSequence { j.nextJarEntry }.map { it.name }.toSet() }
        assertEquals(setOf("binpatch/client/nz.binpatch",
            "binpatch/server/zz.binpatch"), names)
    }

    @Test
    fun `readPatches keys pack records by src class path`() {
        val rec = packRecord("nz patch", "nz", "net.mc.Server",
            exists = true, checksum = 0xABCD, patch = byteArrayOf(9))
        val packJar = jarBytes(mapOf(
            "binpatch/client/nz.binpatch" to rec,
            "binpatch/server/other.binpatch" to packRecord(
                "other", "other", "other", false, 0, byteArrayOf(0))))
        val patches = BinpatchesPackPatcher.readPatches(packJar, "client")
        assertEquals(setOf("nz.class"), patches.keys)
        val p = patches.getValue("nz.class")
        assertEquals("nz patch", p.name)
        assertEquals("nz", p.sourceClassName)
        assertEquals("net.mc.Server", p.targetClassName)
        assertTrue(p.existsAtTarget)
        assertEquals(0xABCD, p.inputChecksum)
    }

    @Test
    fun `readZipPatches keys short records by obf class path`() {
        val rec = zipRecord("nz", "DedicatedServer",
            exists = false, checksum = 0, patch = byteArrayOf(4, 5))
        val archive = lzma("joined.lzma", zipBytes(mapOf(
            "DedicatedServer.binpatch" to rec,
            "notes.txt" to byteArrayOf(0))))
        val patches = BinpatchesPackPatcher.readZipPatches(archive)
        assertEquals(setOf("nz.class"), patches.keys)
        val p = patches.getValue("nz.class")
        assertEquals("nz", p.sourceClassName)
        assertEquals("DedicatedServer", p.targetClassName)
        assertTrue(!p.existsAtTarget)
        assertEquals(0, p.inputChecksum,
            "add-class records carry no checksum")
    }

    // ---------- apply ----------

    @Test
    fun `apply verifies checksum and patches the matching entry`() {
        val a = "orig-bytes".toByteArray()
        val b = "patched-bytes!!".toByteArray()
        val input = jar("in.jar", mapOf(
            "nz.class" to a,
            "keep/K.class" to "keep".toByteArray()))
        val patch = ClassPatch("p", "nz", "nz",
            existsAtTarget = true, inputChecksum = adler(a),
            patch = gdiff(a, b))
        val out = dir.resolve("out.jar")
        BinpatchesPackPatcher.apply(input,
            listOf(SidePatchInput("client", null, mapOf("nz.class" to patch))),
            out)
        assertArrayEquals(b, entries(out)["nz.class"])
        assertEquals("keep", entries(out)["keep/K.class"]!!.decodeToString())
    }

    @Test
    fun `apply checksum mismatch throws binary discrepancy`() {
        val input = jar("in.jar", mapOf("nz.class" to "x".toByteArray()))
        val patch = ClassPatch("p", "nz", "nz",
            existsAtTarget = true, inputChecksum = 0xDEAD,
            patch = byteArrayOf(1))
        val e = assertThrows(RuntimeException::class.java) {
            BinpatchesPackPatcher.apply(input,
                listOf(SidePatchInput("client", null,
                    mapOf("nz.class" to patch))),
                dir.resolve("out.jar"))
        }
        assertTrue(e.message!!.contains("Binary discrepancy"), e.message)
    }

    @Test
    fun `apply add-class patch appends the new entry`() {
        val c = "added-body".toByteArray()
        val input = jar("in.jar", mapOf(
            "nz.class" to "x".toByteArray()))
        val patch = ClassPatch("p", "added/New", "added/New",
            existsAtTarget = false, inputChecksum = 0,
            patch = gdiff(ByteArray(0), c))
        val out = dir.resolve("out.jar")
        BinpatchesPackPatcher.apply(input,
            listOf(SidePatchInput("client", null,
                mapOf("added/New.class" to patch))),
            out)
        assertArrayEquals(c, entries(out)["added/New.class"])
        assertEquals("x", entries(out)["nz.class"]!!.decodeToString())
    }

    @Test
    fun `apply attributes merged entries to the owning side`() {
        // merged jar: entry `s.class` bytes only exist in the server
        // clean jar — the client patch set can't verify it; side
        // checksum attribution picks the server patch
        val s = "server-bytes".toByteArray()
        val sPatched = "server-patched!".toByteArray()
        val input = jar("merged.jar", mapOf("s.class" to s))
        val serverClean = jar("server.jar", mapOf("s.class" to s))
        val clientPatch = ClassPatch("cp", "s", "s",
            existsAtTarget = true, inputChecksum = adler("client".toByteArray()),
            patch = gdiff("client".toByteArray(), "c".toByteArray()))
        val serverPatch = ClassPatch("sp", "s", "s",
            existsAtTarget = true, inputChecksum = adler(s),
            patch = gdiff(s, sPatched))
        val out = dir.resolve("out.jar")
        BinpatchesPackPatcher.apply(input, listOf(
            SidePatchInput("client", null,
                mapOf("s.class" to clientPatch)),
            SidePatchInput("server", serverClean,
                mapOf("s.class" to serverPatch))),
            out)
        assertArrayEquals(sPatched, entries(out)["s.class"])
    }

    @Test
    fun `apply empty-patch record passes bytes through`() {
        val a = "same-bytes".toByteArray()
        val input = jar("in.jar", mapOf("nz.class" to a))
        val patch = ClassPatch("p", "nz", "nz",
            existsAtTarget = true, inputChecksum = adler(a),
            patch = ByteArray(0))
        val out = dir.resolve("out.jar")
        BinpatchesPackPatcher.apply(input,
            listOf(SidePatchInput("client", null,
                mapOf("nz.class" to patch))), out)
        assertArrayEquals(a, entries(out)["nz.class"])
    }

    @Test
    fun `apply writes META-INF entries empty`() {
        val input = jar("in.jar", mapOf(
            "META-INF/TEST.SF" to "sig-body".toByteArray(),
            "a/A.class" to "a".toByteArray()))
        val out = dir.resolve("out.jar")
        BinpatchesPackPatcher.apply(input, emptyList(), out)
        assertEquals(0, entries(out)["META-INF/TEST.SF"]!!.size,
            "signature entries are emptied, not dropped")
        assertEquals("a", entries(out)["a/A.class"]!!.decodeToString())
    }

    @Test
    fun `readZipPatches rejects an archive without binpatch records`() {
        val notZip = lzma("not-zip.lzma", "NFPATCHBUNDLE001".toByteArray())
        assertThrows(java.io.IOException::class.java) {
            BinpatchesPackPatcher.readZipPatches(notZip)
        }
    }

    // ---------- NeoForge patch bundles ----------

    private val create = BundlePatch.Operation.CREATE.bits
    private val modify = BundlePatch.Operation.MODIFY.bits
    private val remove = BundlePatch.Operation.REMOVE.bits
    private val client = PatchBase.CLIENT.mask
    private val server = PatchBase.SERVER.mask
    private val joined = PatchBase.JOINED.mask

    /** One InstallerTools `PatchBundleWriter` entry: flags (operation |
     *  bases), u2 path, checksum (MODIFY only), length, data. */
    private class Entry(
        val flags: Int, val path: String,
        val checksum: Int = 0, val data: ByteArray = ByteArray(0)
    )

    private fun bundleBytes(bases: Int, vararg entries: Entry): ByteArray =
        ByteArrayOutputStream().also { bos ->
            DataOutputStream(bos).use { w ->
                w.write("NFPATCHBUNDLE001".toByteArray())
                w.writeInt(entries.size)
                w.writeByte(bases)
                for (e in entries) {
                    w.writeByte(e.flags)
                    w.writeShort(e.path.length); w.write(e.path.toByteArray())
                    if (e.flags and 0x18 == modify) w.writeInt(e.checksum)
                    w.writeInt(e.data.size); w.write(e.data)
                }
            }
        }.toByteArray()

    private var bundles = 0

    private fun bundle(bases: Int, vararg entries: Entry): Path =
        lzma("patches-${bundles++}.lzma", bundleBytes(bases, *entries))

    @Test
    fun `isPatchBundle tells bundles from zip archives`() {
        assertTrue(BinpatchesPackPatcher.isPatchBundle(bundle(joined)))
        val zip = lzma("joined.lzma", zipBytes(mapOf(
            "nz.binpatch" to zipRecord("nz", "nz", false, 0, byteArrayOf(1)))))
        assertFalse(BinpatchesPackPatcher.isPatchBundle(zip))
    }

    @Test
    fun `readPatchBundle keeps the entries of the requested base`() {
        val archive = bundle(client or server or joined,
            Entry(modify or client or joined, "a/A.class", 7, byteArrayOf(1)),
            Entry(modify or server, "a/A.class", 8, byteArrayOf(2)),
            Entry(create or joined, "META-INF/neoforge.mods.toml", data = byteArrayOf(3)),
            Entry(remove or client, "gone.txt"))
        val joinedPatches = BinpatchesPackPatcher.readPatchBundle(archive, PatchBase.JOINED)
        assertEquals(setOf("a/A.class", "META-INF/neoforge.mods.toml"), joinedPatches.keys)
        val a = joinedPatches.getValue("a/A.class")
        assertEquals(BundlePatch.Operation.MODIFY, a.operation)
        assertEquals(7, a.baseChecksum)
        assertArrayEquals(byteArrayOf(1), a.data)
        val serverPatches = BinpatchesPackPatcher.readPatchBundle(archive, PatchBase.SERVER)
        assertEquals(setOf("a/A.class"), serverPatches.keys)
        assertEquals(8, serverPatches.getValue("a/A.class").baseChecksum)
    }

    @Test
    fun `readPatchBundle rejects a bundle that does not declare the base`() {
        val archive = bundle(client, Entry(create or client, "a.txt"))
        assertThrows(IllegalArgumentException::class.java) {
            BinpatchesPackPatcher.readPatchBundle(archive, PatchBase.JOINED)
        }
    }

    @Test
    fun `readPatchBundle rejects malformed bundles like PatchBundleReader`() {
        val malformed = mapOf(
            "signature" to lzma("sig.lzma", "NFPATCHBUNDLE002".toByteArray() + ByteArray(5)),
            "entry count" to lzma("count.lzma", bundleBytes(joined).also {
                // entry count field follows the 16-byte signature
                it[16] = 0xFF.toByte()
            }),
            "entry type" to bundle(joined, Entry(0x18 or joined, "a.txt")),
            "undeclared entry base" to bundle(joined, Entry(create or server, "a.txt")),
            "REMOVE with data" to bundle(joined,
                Entry(remove or joined, "a.txt", data = byteArrayOf(1))),
            "path character" to bundle(joined, Entry(create or joined, "a\u0001.txt")),
        )
        for ((check, archive) in malformed) {
            assertThrows(java.io.IOException::class.java, {
                BinpatchesPackPatcher.readPatchBundle(archive, PatchBase.JOINED)
            }, check)
        }
    }

    @Test
    fun `readPatchBundle rejects a second entry for a path in the base`() {
        val archive = bundle(client or joined,
            Entry(create or client or joined, "a.txt", data = byteArrayOf(1)),
            Entry(create or joined, "a.txt", data = byteArrayOf(2)))
        val e = assertThrows(java.io.IOException::class.java) {
            BinpatchesPackPatcher.readPatchBundle(archive, PatchBase.JOINED)
        }
        assertTrue(e.message!!.contains("a.txt"), e.message)
    }

    @Test
    fun `applyPatchBundle modifies creates and removes entries`() {
        val a = "orig-bytes".toByteArray()
        val aPatched = "patched-bytes!!".toByteArray()
        val manifest = "Manifest-Version: 1.0\n".toByteArray()
        val input = jar("in.jar", mapOf(
            "META-INF/MANIFEST.MF" to manifest,
            "a/A.class" to a,
            "b/B.class" to "old-b".toByteArray(),
            "gone.txt" to "gone".toByteArray(),
            "keep.txt" to "keep".toByteArray()))
        val patches = BinpatchesPackPatcher.readPatchBundle(bundle(joined,
            Entry(modify or joined, "a/A.class", adler(a), gdiff(a, aPatched)),
            Entry(create or joined, "b/B.class", data = "new-b".toByteArray()),
            Entry(remove or joined, "gone.txt"),
            Entry(create or joined, "META-INF/neoforge.mods.toml",
                data = "modLoader=\"minecraft\"".toByteArray())),
            PatchBase.JOINED)
        val out = dir.resolve("out.jar")
        BinpatchesPackPatcher.applyPatchBundle(input, patches, out)
        val e = entries(out)
        assertArrayEquals(aPatched, e["a/A.class"])
        assertEquals("new-b", e["b/B.class"]!!.decodeToString())
        assertTrue("gone.txt" !in e, "REMOVE drops the entry")
        assertEquals("keep", e["keep.txt"]!!.decodeToString())
        assertArrayEquals(manifest, e["META-INF/MANIFEST.MF"], "META-INF stays verbatim")
        assertEquals("modLoader=\"minecraft\"",
            e["META-INF/neoforge.mods.toml"]!!.decodeToString())
    }

    @Test
    fun `applyPatchBundle checksum mismatch throws`() {
        val input = jar("in.jar", mapOf("a/A.class" to "x".toByteArray()))
        val patches = BinpatchesPackPatcher.readPatchBundle(bundle(joined,
            Entry(modify or joined, "a/A.class", 0xDEAD, byteArrayOf(1))),
            PatchBase.JOINED)
        val e = assertThrows(java.io.IOException::class.java) {
            BinpatchesPackPatcher.applyPatchBundle(input, patches, dir.resolve("out.jar"))
        }
        assertTrue(e.message!!.contains("a/A.class"), e.message)
    }

    @Test
    fun `applyPatchBundle fails on a MODIFY or REMOVE target the base lacks`() {
        val input = jar("in.jar", mapOf("keep.txt" to "keep".toByteArray()))
        for (entry in listOf(
            Entry(modify or joined, "a/A.class", 1, byteArrayOf(1)),
            Entry(remove or joined, "gone.txt"))) {
            val patches = BinpatchesPackPatcher.readPatchBundle(
                bundle(joined, entry), PatchBase.JOINED)
            assertThrows(IllegalStateException::class.java) {
                BinpatchesPackPatcher.applyPatchBundle(input, patches, dir.resolve("out.jar"))
            }
        }
    }

    @Test
    fun `adlerHash matches Adler32 signed int semantics`() {
        val bytes = "hash me".toByteArray()
        val expected = java.util.zip.Adler32().also {
            it.update(bytes) }.value.toInt()
        assertEquals(expected, BinpatchesPackPatcher.adlerHash(bytes))
    }

}
