package xyz.wagyourtail.unimined.provider.native.merge

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.tree.ClassNode
import java.nio.file.FileSystems
import java.nio.file.Path
import java.nio.file.PathMatcher
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.io.path.inputStream
import java.nio.file.Files
import kotlin.io.path.outputStream
import xyz.wagyourtail.unimined.provider.native.internal.io.AtomicIo

/**
 * Merges the client + server jars into a combined dev jar for the
 * `MERGED` side. Server entries win on conflict — server code
 * supersedes client stubs (e.g. `minecraft.jar` bundles client classes
 * the server jar also carries).
 *
 * `META-INF/` is stripped entirely: besides signatures and manifests
 * (neither valid on merged output), the server jar is a Mojang bundler
 * (`versions.list`/`libraries.list` + a nested `versions/<v>.jar`) —
 * keeping that metadata makes Fabric's `BundlerClassPathCapture` unpack
 * a second *obfuscated* server jar onto the launch classpath.
 *
 * Pre-1.13 server jars also *bundle* their runtime libraries inline
 * (com.google, io.netty, it.unimi.dsi.fastutil, …) while
 * the same libraries resolve separately as `minecraftLibraries`
 * dependencies — merging both leaves ~14k duplicate classes in the
 * output (the classes the dependencies win anyway). Stripping is
 * **server-side only and membership-based**: a server entry is dropped
 * only when its path exists inside a resolved library jar (or matches
 * `mergeExcludeGlobs`), never by a package whitelist — the 1.x
 * `includeGlobs` whitelist dropped legitimate client classes whenever
 * they lived outside the hand-maintained prefixes (e.g.
 * unimined/Unimined#215's `com/mojang/renderpearl`).
 */
object JarMerger {

    /**
     * Output-format version — folded into the merged jar's cache name.
     * The merged artifact is produced inside an artifact transform keyed
     * on a constant marker jar, so nothing else invalidates a stale
     * merge: bump this whenever merge semantics change (v2 = pre
     * bundled-library strip).
     */
    const val FORMAT_VERSION = "v4"

    /**
     * Server-side strip controls for [merge].
     *
     * @param libraryJars resolved `minecraftLibraries` jars; server
     *  entries whose path exists inside any of them are bundled copies
     *  and get stripped. Only consulted when
     *  [stripBundledServerLibraries] is on.
     * @param includeGlobs entry globs that are never stripped — an
     *  explicit keep-override that wins over both strip rules.
     * @param excludeGlobs additional entry globs to strip from the
     *  server input — for bundled content absent from [libraryJars]
     *  (undeclared embedded deps).
     * @param stripBundledServerLibraries master switch for the
     *  library-membership strip.
     */
    class Options(
        val libraryJars: List<Path> = emptyList(),
        val includeGlobs: List<String> = emptyList(),
        val excludeGlobs: List<String> = emptyList(),
        val stripBundledServerLibraries: Boolean = true,
    )

    /** Merge [server] onto [client] → [out]. */
    fun merge(client: Path, server: Path, out: Path, options: Options = Options()) {
        val include = options.includeGlobs.map(::pathMatcher)
        val exclude = options.excludeGlobs.map(::pathMatcher)
        val libraryEntries =
            if (options.stripBundledServerLibraries && options.libraryJars.isNotEmpty()) {
                libraryEntryNames(options.libraryJars)
            } else {
                emptySet()
            }

        fun keep(name: String, serverSide: Boolean): Boolean {
            if (!serverSide) return true
            if (include.isNotEmpty() && include.any { it.matches(Path.of(name)) }) return true
            if (exclude.isNotEmpty() && exclude.any { it.matches(Path.of(name)) }) return false
            if (libraryEntries.isNotEmpty() && name in libraryEntries) return false
            return true
        }

        // .class entries merge member-wise (1.x ClassMerger parity):
        // client+server classes diverge (side-only members), so a
        // per-entry overlay silently drops one side's methods/fields
        // (e.g. 1.4.7 Achievement.a(ji) — server class won, client
        // caller broke). Non-class resources keep overlay semantics —
        // server wins conflicts, client fills in server-absent entries
        // (the server jar is the authoritative overlay; 1.x parity).
        val clientClasses = linkedMapOf<String, ClassNode>()
        val serverClasses = linkedMapOf<String, ClassNode>()
        val resourceOrder = mutableListOf<String>()
        val resources = linkedMapOf<String, ByteArray>()

        fun read(jar: Path, serverSide: Boolean) {
            ZipInputStream(jar.inputStream()).use { zin ->
                var e = zin.nextEntry
                while (e != null) {
                    if (e.isDirectory || e.name.startsWith("META-INF/") || !keep(e.name, serverSide)) {
                        e = zin.nextEntry
                        continue
                    }
                    val bytes = zin.readBytes()
                    val node = if (e.name.endsWith(".class")) {
                        // unparseable .class — non-JVM or fixture
                        // content — falls back to verbatim resource
                        // handling rather than failing the merge
                        runCatching {
                            ClassNode().also { ClassReader(bytes).accept(it, 0) }
                        }.getOrNull()
                    } else null
                    if (node != null) {
                        resources.remove(e.name)
                        (if (serverSide) serverClasses else clientClasses)[e.name] = node
                    } else {
                        if (resources.put(e.name, bytes) == null) {
                            resourceOrder.add(e.name)
                        }
                    }
                    e = zin.nextEntry
                }
            }
        }
        read(client, serverSide = false)
        read(server, serverSide = true)

        val merger = ClassMerger()
        val mergedClasses = linkedMapOf<String, ByteArray>()
        for ((name, node) in clientClasses) {
            val serverNode = serverClasses.remove(name)
            val merged = try {
                merger.accept(node, serverNode)
            } catch (e: Exception) {
                throw RuntimeException("Error merging class $name", e)
            }
            mergedClasses[name] = ClassWriter(0).also { merged.accept(it) }.toByteArray()
        }
        for ((name, node) in serverClasses) {
            val merged = merger.accept(null, node)
            mergedClasses[name] = ClassWriter(0).also { merged.accept(it) }.toByteArray()
        }

        val tmp = AtomicIo.tempSibling(out)
        try {
            ZipOutputStream(tmp.outputStream()).use { zout ->
            for ((name, data) in mergedClasses) {
                zout.putNextEntry(java.util.zip.ZipEntry(name))
                zout.write(data)
                zout.closeEntry()
            }
            for (name in resourceOrder) {
                zout.putNextEntry(java.util.zip.ZipEntry(name))
                zout.write(resources.getValue(name))
                zout.closeEntry()
            }
            }
            AtomicIo.moveAtomic(tmp, out)
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    private fun pathMatcher(glob: String): PathMatcher =
        FileSystems.getDefault().getPathMatcher("glob:$glob")

    /** Union of entry names across [jars] — central-directory read only. */
    private fun libraryEntryNames(jars: List<Path>): Set<String> {
        val names = HashSet<String>()
        for (jar in jars) {
            ZipFile(jar.toFile()).use { zf ->
                zf.entries().asSequence().forEach { names.add(it.name) }
            }
        }
        return names
    }

}
