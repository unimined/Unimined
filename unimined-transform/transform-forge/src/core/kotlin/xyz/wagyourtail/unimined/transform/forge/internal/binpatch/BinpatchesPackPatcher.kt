package xyz.wagyourtail.unimined.transform.forge.internal.binpatch

import xyz.wagyourtail.unimined.api.util.NoGroovyTwin

import com.nothome.delta.GDiffPatcher
import org.apache.commons.compress.archivers.zip.ParallelScatterZipCreator
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.apache.commons.compress.compressors.lzma.LZMACompressorInputStream
import org.apache.commons.compress.harmony.unpack200.Pack200UnpackerAdapter
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.jar.JarInputStream
import java.util.jar.JarOutputStream
import java.util.zip.Adler32
import java.util.zip.ZipFile
import xyz.wagyourtail.unimined.api.util.writeAtomically
import kotlin.io.path.outputStream

/**
 * In-process binpatch codecs and appliers for the Forge-family formats:
 * - FG2-era `binpatches.pack.lzma` ([unpackPack]/[readPatches]) — a
 *   faithful port of 1.x's `FG2TaskApplyBinPatches` (itself a port of
 *   Forge's `TaskApplyBinPatches`): lzma→pack200→jar of
 *   `binpatch/<side>/...binpatch` GDiff patches;
 * - userdev/installer `.lzma` archives ([readZipPatches]) — lzma → flat
 *   zip of `.binpatch` records.
 *
 * Both are applied by [apply]: each jar entry whose name matches a
 * patch's source class is verified (adler32 checksum) and replaced with
 * the patched bytes. Unmatched entries are copied verbatim; META-INF
 * entries keep their name and time but are written empty, like 1.x.
 *
 * NeoForge's binarypatcher 4.x bundles (`NFPATCHBUNDLE001`, e.g. the
 * NeoForge 26.3 userdev and installer) are read by [readPatchBundle] and applied
 * by [applyPatchBundle] — a port of InstallerTools' `PatchBundleReader`
 * and `Patcher`.
 *
 * Gradle-free: takes a `log` sink so it can run inside step tasks,
 * artifact transforms, or tests.
 */
@NoGroovyTwin("binpatch pack patcher — programmatic engine API")
object BinpatchesPackPatcher {

    data class ClassPatch(
        val name: String,
        val sourceClassName: String,
        val targetClassName: String,
        val existsAtTarget: Boolean,
        val inputChecksum: Int,
        val patch: ByteArray
    )

    /**
     * Decompress `binpatches.pack.lzma` at [packFile] once — returns the
     * pack200-unpacked jar bytes. Decompression+unpack is the
     * expensive part of [readPatches]; shared when both `client` and
     * `server` patch sets are read for a joined environment.
     */
    fun unpackPack(packFile: Path): ByteArray =
        LZMACompressorInputStream(
            packFile.toFile().inputStream().buffered()
        ).use { uncompressed ->
            ByteArrayOutputStream().also { jarBytes ->
                JarOutputStream(jarBytes).use {
                    Pack200UnpackerAdapter().unpack(uncompressed, it)
                }
            }.toByteArray()
        }

    /**
     * The `binpatch/<side>/` sides present in [packJarBytes] (from
     * [unpackPack]) — universal `binpatches.pack` archives split
     * `client`/`server`, FG2 dev packages (`devbinpatches.pack`)
     * carry a single `merged` set authored against the merged dev
     * jar.
     */
    fun packSides(packJarBytes: ByteArray): Set<String> {
        val sides = LinkedHashSet<String>()
        JarInputStream(ByteArrayInputStream(packJarBytes)).use { jar ->
            while (true) {
                val entry = jar.nextJarEntry ?: break
                if (entry.name.startsWith("binpatch/") &&
                    entry.name.endsWith(".binpatch")
                ) {
                    sides.add(
                        entry.name.substringAfter('/')
                            .substringBefore('/')
                    )
                }
            }
        }
        return sides
    }

    /**
     * Read the `binpatch/&lt;side&gt;/` entries of [packJarBytes]
     * (from [unpackPack]), keyed by their jar entry name
     * (`com/example/Foo.class`).
     */
    fun readPatches(packJarBytes: ByteArray, side: String): Map<String, ClassPatch> {
        val prefix = "binpatch/$side/"
        val patches = LinkedHashMap<String, ClassPatch>()
        JarInputStream(ByteArrayInputStream(packJarBytes)).use { jar ->
            while (true) {
                val entry = jar.nextJarEntry ?: break
                if (entry.name.startsWith(prefix) && entry.name.endsWith(".binpatch")) {
                    val cp = readPatch(jar)
                    patches[cp.sourceClassName.replace('.', '/') + ".class"] = cp
                }
            }
        }
        return patches
    }

    private fun readPatch(jis: JarInputStream): ClassPatch {
        val input = DataInputStream(jis)
        val name = input.readUTF()
        val sourceClassName = input.readUTF()
        val targetClassName = input.readUTF()
        val exists = input.readBoolean()
        val inputChecksum = if (exists) input.readInt() else 0
        val patchBytes = ByteArray(input.readInt())
        input.readFully(patchBytes)
        return ClassPatch(
            name, sourceClassName, targetClassName,
            exists, inputChecksum, patchBytes
        )
    }

    /**
     * Read a modern userdev `binpatches` archive ([lzmaFile] —
     * lzma-compressed zip of flat `&lt;srcName&gt;.binpatch` entries).
     * The record is the short form of the pack-era layout: a flag byte
     * (obf-name marker), then the **input** jar's class name UTF
     * (`obfName` — e.g. `nz`) and the **output** class name UTF
     * (e.g. `net/minecraft/server/dedicated/DedicatedServer` — the
     * patch renames the entry when they differ), then `exists`,
     * `checksum`, `patchLen`, GDiff bytes. Keyed by the INPUT jar
     * entry name like [readPatches]. An archive without a single
     * `.binpatch` record is rejected — a payload in another format
     * reads as an empty zip.
     */
    fun readZipPatches(lzmaFile: Path): Map<String, ClassPatch> {
        val patches = LinkedHashMap<String, ClassPatch>()
        val zipBytes = LZMACompressorInputStream(
            lzmaFile.toFile().inputStream().buffered()
        ).use { it.readBytes() }
        java.util.zip.ZipInputStream(ByteArrayInputStream(zipBytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.name.endsWith(".binpatch")) continue
                val input = DataInputStream(zip)
                input.readByte() // obf-name marker
                val obfName = input.readUTF()
                val name = input.readUTF()
                val exists = input.readBoolean()
                // checksum only present for exists=true records —
                // add-class records go straight to patchLen
                val inputChecksum = if (exists) input.readInt() else 0
                val patchBytes = ByteArray(input.readInt())
                input.readFully(patchBytes)
                val cp = ClassPatch(
                    name, obfName, name,
                    exists, inputChecksum, patchBytes
                )
                patches["$obfName.class"] = cp
            }
        }
        if (patches.isEmpty()) {
            throw IOException("${lzmaFile.fileName} holds no .binpatch records")
        }
        return patches
    }

    /** binarypatcher's base distributions (InstallerTools `PatchBase`) —
     *  the jar a patch-bundle entry applies to, as its bundle bit. */
    enum class PatchBase(val mask: Int) {
        CLIENT(0x01),
        SERVER(0x02),
        JOINED(0x04);

        companion object {
            fun of(bits: Int): List<PatchBase> = entries.filter { bits and it.mask != 0 }
        }
    }

    /** One patch-bundle entry (InstallerTools `Patch`): [data] is the
     *  GDiff patch for [Operation.MODIFY] (verified against
     *  [baseChecksum], the base entry's adler32), the file itself for
     *  [Operation.CREATE], empty for [Operation.REMOVE]. */
    class BundlePatch(
        val operation: Operation,
        val path: String,
        val baseChecksum: Int,
        val data: ByteArray
    ) {
        /** The entry operation, as its bundle bits. */
        enum class Operation(val bits: Int) {
            CREATE(0x00),
            MODIFY(0x08),
            REMOVE(0x10)
        }
    }

    private val BUNDLE_SIGNATURE = "NFPATCHBUNDLE001".toByteArray(Charsets.US_ASCII)
    private const val ENTRY_TYPE_MASK = 0x18
    private const val DISTRIBUTION_MASK = 0x07

    /** Whether the lzma payload at [lzmaFile] is a NeoForge patch bundle
     *  rather than a zip of `.binpatch` records. */
    fun isPatchBundle(lzmaFile: Path): Boolean =
        LZMACompressorInputStream(lzmaFile.toFile().inputStream().buffered()).use {
            it.readNBytes(BUNDLE_SIGNATURE.size).contentEquals(BUNDLE_SIGNATURE)
        }

    /**
     * Read the [base] entries of the NeoForge patch bundle at
     * [lzmaFile], keyed by target path — InstallerTools
     * `PatchBundleReader` with every check it makes, plus `Patcher`'s
     * refusal of a bundle that does not declare [base]. binarypatcher
     * writes at most one entry per path and base; a second one is
     * rejected, not overwritten.
     */
    fun readPatchBundle(lzmaFile: Path, base: PatchBase): Map<String, BundlePatch> =
        DataInputStream(
            LZMACompressorInputStream(lzmaFile.toFile().inputStream().buffered())
        ).use { input ->
            val signature = ByteArray(BUNDLE_SIGNATURE.size).also(input::readFully)
            if (!signature.contentEquals(BUNDLE_SIGNATURE)) {
                throw IOException("Invalid bundle signature in ${lzmaFile.fileName}")
            }
            val entryCount = input.readInt()
            if (entryCount < 0) throw IOException("Invalid entry count: $entryCount")
            val bundleBases = input.readUnsignedByte() and DISTRIBUTION_MASK
            if (bundleBases and base.mask == 0) {
                throw IllegalArgumentException(
                    "Cannot apply patch bundle ${lzmaFile.fileName} to $base because " +
                            "it only applies to the base types ${PatchBase.of(bundleBases)}"
                )
            }
            val patches = LinkedHashMap<String, BundlePatch>()
            repeat(entryCount) {
                val flags = input.readUnsignedByte()
                val type = flags and ENTRY_TYPE_MASK
                val operation = BundlePatch.Operation.entries.firstOrNull { it.bits == type }
                    ?: throw IOException("Unknown entry type: $type")
                val entryBases = flags and DISTRIBUTION_MASK
                if (entryBases and bundleBases.inv() != 0) {
                    throw IOException(
                        "Entry targets distribution " +
                                "${PatchBase.of(entryBases and bundleBases.inv())} not declared in bundle"
                    )
                }
                val path = readBundleString(input)
                val baseChecksum =
                    if (operation == BundlePatch.Operation.MODIFY) input.readInt() else 0
                val length = input.readInt()
                if (length < 0) throw IOException("Invalid data length: $length")
                if (operation == BundlePatch.Operation.REMOVE && length != 0) {
                    throw IOException("REMOVE entry must have data length of 0")
                }
                val data = ByteArray(length).also(input::readFully)
                if (entryBases and base.mask == 0) return@repeat
                if (patches.put(path, BundlePatch(operation, path, baseChecksum, data)) != null) {
                    throw IOException(
                        "Patch bundle ${lzmaFile.fileName} has two $base entries for $path"
                    )
                }
            }
            patches
        }

    /** A bundle string: u2 length + printable-ASCII bytes. */
    private fun readBundleString(input: DataInputStream): String {
        val bytes = ByteArray(input.readUnsignedShort()).also(input::readFully)
        bytes.firstOrNull { it < 0x20 || it > 0x7E }?.let {
            throw IOException(
                "String contains invalid character: 0x" + Integer.toHexString(it.toInt() and 0xFF)
            )
        }
        return String(bytes, Charsets.US_ASCII)
    }

    /** One jar entry's staged data — `data` is null for directories. */
    private data class EntryData(
        val name: String,
        val time: Long,
        val key: String,
        val data: ByteArray?
    )

    /** One side's patch set plus the clean jar it was built against —
     *  used to attribute merged-jar entries to their origin side. */
    data class SidePatchInput(
        val side: String,
        val jar: Path?,
        val patches: Map<String, ClassPatch>
    )

    private fun sideChecksums(jar: Path?): Map<String, Int> {
        if (jar == null) return emptyMap()
        val out = HashMap<String, Int>()
        ZipFile(jar.toFile()).use { z ->
            for (e in z.entries()) {
                if (e.isDirectory) continue
                out[e.name.replace('\\', '/')] =
                    adlerHash(z.getInputStream(e).readBytes())
            }
        }
        return out
    }

    /**
     * Apply the per-side patch sets to [input] → [output]
     * (1.x `merge`: `transformIntern(clientjar)` +
     * `transformIntern(serverjar)` then merge — binpatches run on
     * each side's clean jar *before* merging). On an already-merged
     * jar the equivalent is per-entry side attribution: each entry's
     * bytes are adler-compared against both clean side jars and the
     * matching side's patch is applied; entries matching neither
     * side try either patch set; `exists=false` patches are add-class
     * entries applied against empty bytes (no checksum) and, when
     * absent from the input, appended as new entries.
     */
    fun apply(
        input: Path,
        sideInputs: List<SidePatchInput>,
        output: Path,
        log: (String) -> Unit = {},
        /** optional inline progress — `(done, total)` per input entry
         *  patched and per output entry written; called from worker
         *  threads, so throttle inside the callback */
        onProgress: ((done: Int, total: Int) -> Unit)? = null
    ) {
        // per-side entry adler maps — built lazily: only needed to
        // attribute entries whose patch candidates can't be verified
        // directly (ambiguous or unpatched-side entries); hashing both
        // ~30MB side jars eagerly costs seconds per cold run
        val sideChecksums = sideInputs.map {
            lazy(LazyThreadSafetyMode.SYNCHRONIZED) { sideChecksums(it.jar) }
        }
        // GDiffPatcher isn't thread-safe — one per worker thread
        val patcher = ThreadLocal.withInitial { GDiffPatcher() }
        val applied = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

        fun patchEntry(key: String, data: ByteArray): ByteArray {
            // 1.x parity: META-INF entries are written empty —
            // strips signatures (signed jars would fail
            // verification once classes are patched)
            if (key.contains("META-INF")) return ByteArray(0)
            val inputChecksum = adlerHash(data)
            val candidates = sideInputs.asSequence()
                .mapNotNull { it.patches[key] }.toList()
            if (candidates.isEmpty()) return data
            // the patch whose expected checksum matches the entry
            // bytes is the origin side's patch — verified patches
            // win over add-class entries
            var patch = candidates.firstOrNull {
                it.existsAtTarget && it.inputChecksum == inputChecksum
            }
            if (patch == null) {
                // no direct match — attribute the entry to its
                // side via the side jars (lazy, once)
                val owner = sideChecksums.indexOfFirst {
                    it.value[key] == inputChecksum
                }.takeIf { it >= 0 }?.let { sideInputs[it] }
                patch = owner?.patches?.get(key)
                if (patch == null && owner == null) {
                    // foreign entry: only an add patch can apply —
                    // a verifying-type patch that can't match is
                    // a real discrepancy
                    patch = candidates.firstOrNull {
                        !it.existsAtTarget
                    } ?: candidates.firstOrNull()?.let {
                        throw RuntimeException(
                            "Binary discrepancy: expected input class " +
                                    "${it.targetClassName} (${it.sourceClassName}) " +
                                    "checksum ${Integer.toHexString(inputChecksum)} on disk, " +
                                    "${Integer.toHexString(it.inputChecksum)} in patch"
                        )
                    }
                }
            }
            // attributed owner has no patch for this entry — copy the
            // clean bytes through
            if (patch == null) return data
            val bytes = if (!patch.existsAtTarget) {
                // add-class patch — the merged jar already carries
                // the class from the other side; apply against
                // empty bytes
                patcher.get().patch(ByteArray(0), patch.patch)
            } else {
                if (patch.inputChecksum != inputChecksum) {
                    throw RuntimeException(
                        "Binary discrepancy: expected input class " +
                                "${patch.targetClassName} (${patch.sourceClassName}) " +
                                "checksum ${Integer.toHexString(inputChecksum)} on disk, " +
                                "${Integer.toHexString(patch.inputChecksum)} in patch"
                    )
                }
                if (patch.patch.isEmpty()) {
                    // rename/no-change record — verified input
                    // passes through, entry may rename
                    data
                } else try {
                    patcher.get().patch(data, patch.patch)
                } catch (e: Exception) {
                    throw RuntimeException(
                        "binpatch ${patch.name} -> ${patch.targetClassName} " +
                                "(src ${patch.sourceClassName}, exists=${patch.existsAtTarget}, " +
                                "patchLen=${patch.patch.size}) failed for entry $key", e
                    )
                }
            }
            // zip-era record `name` is the mcp/record label, NOT
            // the output name — patched classes keep obf internal
            // names (`nz`, `hh`); the FG2 dev jar stays
            // obf-named and runtime deobf maps it srg→mcp
            applied.add(key)
            return bytes
        }

        patchJar(input, output, onProgress, ::patchEntry) {
            // add-class patches for classes the input jar doesn't
            // carry at all — patched from empty bytes in parallel too
            sideInputs.flatMap { s -> s.patches.entries.map { s.side to it } }
                .parallelStream()
                .filter { (_, kv) ->
                    val (key, patch) = kv
                    // applied-set membership checks precede the mark so
                    // `name == key` records aren't self-excluded; the
                    // add() return is the concurrent dedup guard
                    !patch.existsAtTarget && key !in applied &&
                            (patch.name.replace('.', '/') + ".class") !in applied &&
                            applied.add(key)
                }
                .map { (side, kv) ->
                    val (_, patch) = kv
                    // `name` is the entry path (obf or srg-slash);
                    // `sourceClassName` is dot-form on srg records
                    // — emitting it verbatim would write
                    // `net.minecraft.X.class` entries
                    val name = patch.name.replace('.', '/') + ".class"
                    Triple(side, name, patcher.get().patch(ByteArray(0), patch.patch))
                }
                .toList()
                .map { (side, name, bytes) ->
                    log("added $name ($side patch)")
                    EntryData(name, 0L, name, bytes)
                }
        }
    }

    /**
     * Apply a NeoForge patch bundle's [patches] (from
     * [readPatchBundle]) to [input] → [output] — InstallerTools
     * `Patcher`: MODIFY verifies the entry's adler32 before the GDiff
     * patch, CREATE writes its bytes (replacing an existing entry),
     * REMOVE drops the entry; CREATE paths the input lacks are
     * appended, and MODIFY/REMOVE paths it lacks fail. META-INF stays
     * verbatim like binarypatcher's output — the patches create the
     * `minecraft` mod's `META-INF/neoforge.mods.toml`.
     */
    fun applyPatchBundle(
        input: Path,
        patches: Map<String, BundlePatch>,
        output: Path,
        log: (String) -> Unit = {},
        /** optional inline progress — as for [apply] */
        onProgress: ((done: Int, total: Int) -> Unit)? = null
    ) {
        // GDiffPatcher isn't thread-safe — one per worker thread
        val patcher = ThreadLocal.withInitial { GDiffPatcher() }
        val present = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

        fun patchEntry(key: String, data: ByteArray): ByteArray? {
            val patch = patches[key] ?: return data
            present.add(key)
            return when (patch.operation) {
                BundlePatch.Operation.CREATE -> patch.data
                BundlePatch.Operation.REMOVE -> null
                BundlePatch.Operation.MODIFY -> {
                    val checksum = adlerHash(data)
                    if (checksum != patch.baseChecksum) {
                        throw IOException(
                            "Patch expected $key to have the checksum " +
                                    "${Integer.toHexString(patch.baseChecksum)} but it was " +
                                    Integer.toHexString(checksum)
                        )
                    }
                    try {
                        patcher.get().patch(data, patch.data)
                    } catch (e: Exception) {
                        throw IOException("binpatch for $key failed", e)
                    }
                }
            }
        }

        patchJar(input, output, onProgress, ::patchEntry) {
            patches.values.filter { it.path !in present }.map { patch ->
                when (patch.operation) {
                    BundlePatch.Operation.CREATE -> {
                        log("added ${patch.path}")
                        EntryData(patch.path, 0L, patch.path, patch.data)
                    }
                    BundlePatch.Operation.MODIFY -> throw IllegalStateException(
                        "Patch targets ${patch.path}, but it does not exist in the base."
                    )
                    BundlePatch.Operation.REMOVE -> throw IllegalStateException(
                        "Somehow ${patch.path} was deleted although it does not exist."
                    )
                }
            }
        }
    }

    /**
     * The jar pass [apply] and [applyPatchBundle] share: every file
     * entry of [input] is read and run through [transform] (its
     * entry-name key and bytes → output bytes, null drops the entry) in
     * one parallel pass — the per-entry inflate is CPU-bound like the
     * GDiff patch itself, so it rides the same worker pool instead of a
     * serial front-end (ZipFile.getInputStream is thread-safe:
     * independent inflater streams per call). [appended] runs after
     * that pass; its entries follow the input's. Written via
     * temp+atomic move — a killed patch run must not leave a partial
     * (or deleted-then-partial) jar (#78).
     */
    private fun patchJar(
        input: Path,
        output: Path,
        onProgress: ((done: Int, total: Int) -> Unit)?,
        transform: (key: String, data: ByteArray) -> ByteArray?,
        appended: () -> List<EntryData>
    ) {
        output.writeAtomically { tmp ->
            ZipFile(input.toFile()).use { `in` ->
                val patchCount = java.util.concurrent.atomic.AtomicInteger()
                // encounter order preserved by the ordered stream
                val entries = `in`.entries().asSequence().toList()
                    .parallelStream().map { e ->
                        onProgress?.invoke(patchCount.incrementAndGet(), `in`.size())
                        val key = e.name.replace('\\', '/')
                        if (e.isDirectory) EntryData(e.name, e.time, key, null)
                        else transform(key, `in`.getInputStream(e).readBytes())
                            ?.let { EntryData(e.name, e.time, key, it) }
                    }.toList().filterNotNull()
                writeJar(tmp, entries + appended())
            }
        }
    }

    /**
     * Parallel per-entry deflate, ordered gather — the same pattern
     * JarMerger/AsmRemapper ride; output stays deterministic because
     * entry times and the emittedDirs walk are replicated verbatim.
     */
    private fun writeJar(out: Path, entries: List<EntryData>) {
        val pool = Executors.newFixedThreadPool(
            Runtime.getRuntime().availableProcessors()
        )
        try {
            val creator = ParallelScatterZipCreator(pool)
            val emittedDirs = HashSet<String>()
            // mirrors commonskt putEntryWithDirs: missing `dir/`
            // parents emitted once, in first-occurrence position,
            // stamped with their child's time
            fun addWithDirs(name: String, time: Long, data: ByteArray?) {
                var i = name.indexOf('/')
                while (i != -1) {
                    val dir = name.substring(0, i + 1)
                    if (emittedDirs.add(dir)) {
                        creator.addArchiveEntry(
                            ZipArchiveEntry(dir).apply {
                                method = ZipArchiveEntry.DEFLATED
                                this.time = time
                            }
                        ) { ByteArrayInputStream(ByteArray(0)) }
                    }
                    i = name.indexOf('/', i + 1)
                }
                // explicit `dir/` input entry — the parent loop
                // already emitted it; a second write would dup
                if (name.endsWith('/')) return
                creator.addArchiveEntry(
                    ZipArchiveEntry(name).apply {
                        method = ZipArchiveEntry.DEFLATED
                        this.time = time
                    }
                ) { ByteArrayInputStream(data ?: ByteArray(0)) }
            }
            for (rec in entries) {
                addWithDirs(rec.name, rec.time, rec.data)
            }
            out.outputStream().buffered().let {
                ZipArchiveOutputStream(it)
            }.use { zout ->
                creator.writeTo(zout)
            }
        } finally {
            pool.shutdown()
        }
    }

    /** adler32 of [data] as a signed int (Forge patch checksums). */
    fun adlerHash(data: ByteArray): Int {
        val hasher = Adler32()
        hasher.update(data)
        return hasher.value.toInt()
    }
}
