package xyz.wagyourtail.unimined.provider.native.merge

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.tree.AnnotationNode
import org.objectweb.asm.tree.ClassNode
import xyz.wagyourtail.unimined.mapping.EnvType
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
    const val FORMAT_VERSION = "v6"

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
        /** Side-marker annotations injected on single-side
         *  class/field/method content — the merge consumer passes
         *  the ecosystem its env expects (patch checksums are
         *  era-canonical, so extra markers break them). */
        val sideMarkers: List<SideMarker> = listOf(SideMarker.FORGE_FML),
    )

    /** A sided annotation stamped on single-side content — e.g.
     *  forge `SideOnly(Side.CLIENT)` or fabric
     *  `Environment(EnvType.CLIENT)`. */
    class SideMarker(
        /** annotation internal name, e.g.
         *  `cpw/mods/fml/relauncher/SideOnly` */
        val annotation: String,
        /** enum internal name for the value, e.g.
         *  `cpw/mods/fml/relauncher/Side` */
        val enumType: String,
        /** enum constant for client-side content. */
        val clientValue: String,
        /** enum constant for server-side content. */
        val serverValue: String,
    ) : java.io.Serializable {
        companion object {
            /** FML-era forge ≤1.7.x: `cpw.mods.fml.relauncher.SideOnly`. */
            val FORGE_CPMODS = SideMarker(
                "cpw/mods/fml/relauncher/SideOnly",
                "cpw/mods/fml/relauncher/Side", "CLIENT", "SERVER")
            /** FML-era forge 1.8–1.12.x: `net.minecraftforge.fml
             *  .relauncher.SideOnly` — what mergetool injects for
             *  FG2-era (e.g. 1.12.2 userdev3) merges. */
            val FORGE_FML = SideMarker(
                "net/minecraftforge/fml/relauncher/SideOnly",
                "net/minecraftforge/fml/relauncher/Side",
                "CLIENT", "SERVER")
            /** Modern forge (1.13+): `net.minecraftforge.api
             *  .distmarker.OnlyIn`. */
            val FORGE_MODERN = SideMarker(
                "net/minecraftforge/api/distmarker/OnlyIn",
                "net/minecraftforge/api/distmarker/Dist",
                "CLIENT", "DEDICATED_SERVER")
            /** NeoForge: `net.neoforged.api.distmarker.OnlyIn`. */
            val NEOFORGE = SideMarker(
                "net/neoforged/api/distmarker/OnlyIn",
                "net/neoforged/api/distmarker/Dist",
                "CLIENT", "DEDICATED_SERVER")
            /** Fabric: `net.fabricmc.api.Environment`. */
            val FABRIC = SideMarker(
                "net/fabricmc/api/Environment",
                "net/fabricmc/api/EnvType", "CLIENT", "SERVER")
        }
    }

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
        // raw entry bytes alongside the parsed node — needed so
        // byte-identical classes can be emitted verbatim (the
        // canonical MCP-era merge never rewrites an entry whose
        // sides agree; joined binpatches checksum those bytes)
        val clientClassBytes = linkedMapOf<String, ByteArray>()
        val serverClassBytes = linkedMapOf<String, ByteArray>()
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
                        (if (serverSide) serverClassBytes else clientClassBytes)[e.name] = bytes
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

        // side-only content gets the canonical
        // `@SideOnly(Side.CLIENT|SERVER)` marker — FML-era merges
        // (and the binpatch checksums built against them) carry the
        // annotation on every single-side class/field/method
        fun addSideOnly(annotations: MutableList<AnnotationNode>?,
                        set: (MutableList<AnnotationNode>) -> Unit,
                        side: EnvType) {
            val target = annotations ?: mutableListOf<AnnotationNode>().also(set)
            for (marker in options.sideMarkers) {
                target.add(AnnotationNode("L${marker.annotation};").apply {
                    values = listOf("value", arrayOf(
                        "L${marker.enumType};",
                        if (side == EnvType.CLIENT) marker.clientValue
                        else marker.serverValue))
                })
            }
        }
        val merger = ClassMerger(
            toMergedClass = { node, side ->
                addSideOnly(node.visibleAnnotations,
                    { node.visibleAnnotations = it }, side)
            },
            toMergedFields = { node, side ->
                addSideOnly(node.visibleAnnotations,
                    { node.visibleAnnotations = it }, side)
            },
            toMergedMethods = { node, side ->
                addSideOnly(node.visibleAnnotations,
                    { node.visibleAnnotations = it }, side)
            },
        )
        // merge+serialize fans out — ClassMerger is stateless and
        // each pair owns its ClassNodes; `toList()` on an ordered
        // parallel stream preserves encounter order, so the output
        // jar ordering stays deterministic
        val mergedClasses = linkedMapOf<String, ByteArray>()
        clientClasses.entries.toList().parallelStream().map { (name, node) ->
            val serverNode = serverClasses[name]
            name to try {
                // byte-identical sides emit verbatim — the canonical
                // merge the binpatch checksums were built against
                // never rewrites an entry both sides agree on (ASM
                // round-trip perturbs constant-pool order etc.)
                val cb = clientClassBytes[name]
                val sb = serverClassBytes[name]
                if (serverNode != null && cb != null && cb.contentEquals(sb)) cb
                else ClassWriter(0)
                    .also { merger.accept(node, serverNode).accept(it) }
                    .toByteArray()
            } catch (e: Exception) {
                throw RuntimeException("Error merging class $name", e)
            }
        }.toList().forEach { (name, bytes) ->
            mergedClasses[name] = bytes
            serverClasses.remove(name)
        }
        serverClasses.entries.toList().parallelStream().map { (name, node) ->
            name to ClassWriter(0)
                .also { merger.accept(null, node).accept(it) }
                .toByteArray()
        }.toList().forEach { (name, bytes) -> mergedClasses[name] = bytes }

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
