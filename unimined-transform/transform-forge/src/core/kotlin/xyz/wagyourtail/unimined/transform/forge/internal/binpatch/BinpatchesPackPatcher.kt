package xyz.wagyourtail.unimined.transform.forge.internal.binpatch

import com.nothome.delta.GDiffPatcher
import org.apache.commons.compress.archivers.zip.ParallelScatterZipCreator
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.apache.commons.compress.compressors.lzma.LZMACompressorInputStream
import org.apache.commons.compress.harmony.unpack200.Pack200UnpackerAdapter
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.jar.JarInputStream
import java.util.jar.JarOutputStream
import java.util.zip.Adler32
import java.util.zip.ZipFile
import xyz.wagyourtail.unimined.api.util.writeAtomically
import kotlin.io.path.outputStream

/**
 * FG2-era `binpatches.pack.lzma` application — a faithful port of
 * 1.x's `FG2TaskApplyBinPatches` (itself a port of Forge's
 * `TaskApplyBinPatches`). The pack is lzma→pack200→jar of
 * `binpatch/<side>/...binpatch` GDiff patches; each jar entry whose
 * name matches a patch's source class is verified (adler32 checksum)
 * and replaced with the patched bytes. Unmatched entries are copied
 * verbatim (META-INF preserved by name+time like 1.x).
 *
 * Gradle-free: takes a `log` sink so it can run inside step tasks,
 * artifact transforms, or tests.
 */
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
     * entry name like [readPatches].
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
        return patches
    }

    /** One jar entry's staged data — `data` is null for signature
     *  metadata (written as an empty entry per 1.x). */
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
        // write via temp+atomic move — a killed patch run must not
        // leave a partial (or deleted-then-partial) jar (#78)
        output.writeAtomically { tmp ->
        // per-side entry adler maps — built lazily: only needed to
        // attribute entries whose patch candidates can't be verified
        // directly (ambiguous or unpatched-side entries); hashing both
        // ~30MB side jars eagerly costs seconds per cold run
        val sideChecksums = sideInputs.map {
            lazy(LazyThreadSafetyMode.SYNCHRONIZED) { sideChecksums(it.jar) }
        }
        // GDiffPatcher isn't thread-safe — one per worker thread; the
        // patch stage fans out over fork/join while the jar write stays
        // sequential (encounter order preserved by the ordered stream)
        val patcher = ThreadLocal.withInitial { GDiffPatcher() }
        val applied = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        ZipFile(input.toFile()).use { `in` ->
            val patchCount = java.util.concurrent.atomic.AtomicInteger()
            // read+patch fused in one parallel pass — the per-entry
            // inflate is CPU-bound like the GDiff patch itself, so it
            // rides the same worker pool instead of a serial front-end
            // (ZipFile.getInputStream is thread-safe: independent
            // inflater streams per call)
            val patched = `in`.entries().asSequence().toList()
                .parallelStream().map { e ->
                onProgress?.invoke(patchCount.incrementAndGet(), `in`.size())
                // 1.x parity: META-INF entries are written empty —
                // strips signatures (signed jars would fail
                // verification once classes are patched)
                val rec = EntryData(
                    e.name, e.time, e.name.replace('\\', '/'),
                    if (e.isDirectory || e.name.contains("META-INF")) null
                    else `in`.getInputStream(e).readBytes()
                )
                val data = rec.data ?: return@map rec
                val inputChecksum = adlerHash(data)
                val candidates = sideInputs.asSequence()
                    .mapNotNull { it.patches[rec.key] }.toList()
                if (candidates.isEmpty()) return@map rec
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
                        it.value[rec.key] == inputChecksum
                    }.takeIf { it >= 0 }?.let { sideInputs[it] }
                    patch = owner?.patches?.get(rec.key)
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
                if (patch == null) {
                    // attributed owner has no patch for this entry —
                    // copy the clean bytes through
                    return@map rec
                }
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
                                    "patchLen=${patch.patch.size}) failed for entry ${rec.key}", e
                        )
                    }
                }
                applied.add(rec.key)
                // zip-era record `name` is the mcp/record label, NOT
                // the output name — patched classes keep obf internal
                // names (`nz`, `hh`); the FG2 dev jar stays
                // obf-named and runtime deobf maps it srg→mcp
                EntryData(rec.name, rec.time, rec.key, bytes)
            }.toList()
            // add-class patches for classes the input jar doesn't
            // carry at all — patched from empty bytes in parallel too
            val added = sideInputs.flatMap { s -> s.patches.entries.map { s.side to it } }
                .parallelStream()
                .filter { (_, kv) ->
                    val (key, patch) = kv
                    // applied-set membership checks precede the mark so
                    // `name == key` records aren't self-excluded; the
                    // add() return is the concurrent dedup guard
                    !patch.existsAtTarget && key !in applied &&
                            (patch.name + ".class") !in applied &&
                            applied.add(key)
                }
                .map { (side, kv) ->
                    val (_, patch) = kv
                    Triple(
                        side,
                        // add-classes keep their obf entry name too
                        patch.sourceClassName + ".class",
                        patcher.get().patch(ByteArray(0), patch.patch)
                    )
                }
                .toList()
            // parallel per-entry deflate, ordered gather — the same
            // pattern JarMerger/AsmRemapper ride; output stays
            // deterministic because original entry times and the
            // emittedDirs walk are replicated verbatim
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
                for (rec in patched) {
                    addWithDirs(rec.name, rec.time, rec.data)
                }
                for ((side, name, bytes) in added) {
                    addWithDirs(name, 0L, bytes)
                    log("added $name ($side patch)")
                }
                tmp.outputStream().buffered().let {
                    ZipArchiveOutputStream(it)
                }.use { zout ->
                    creator.writeTo(zout)
                }
            } finally {
                pool.shutdown()
            }
        }
        }
    }

    /** adler32 of [data] as a signed int (Forge patch checksums). */
    fun adlerHash(data: ByteArray): Int {
        val hasher = Adler32()
        hasher.update(data)
        return hasher.value.toInt()
    }
}
