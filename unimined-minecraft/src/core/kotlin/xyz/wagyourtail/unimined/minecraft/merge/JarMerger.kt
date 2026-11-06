package xyz.wagyourtail.unimined.minecraft.merge

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
import xyz.wagyourtail.commonskt.jvm.moveAtomicTo
import xyz.wagyourtail.commonskt.jvm.tempSibling

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
    const val FORMAT_VERSION = "v7"

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
         *  class/field/method content — opt-in per merge consumer:
         *  pass the set its env's ecosystem expects (patch
         *  checksums are era-canonical, so extra markers break
         *  them). Empty by default — the loader-agnostic native
         *  merge has no marker consumer, and foreign annotations
         *  surface as javac "unknown enum constant" warnings on
         *  every compile classpath the jar lands on. */
        val sideMarkers: List<SideMarker> = emptyList(),
        /** Custom merge behavior consulted in order before the
         *  built-ins ([ServiceFileMerger], [MemberMerger], then
         *  client-wins for resources) — divergent-member strategies
         *  (dispatch/rename) register here. */
        val extensions: List<MergeExtension> = emptyList(),
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
                BundledLibraryStrip.entryNames(options.libraryJars)
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
        // client wins conflicts, server fills in client-absent entries
        // (the client jar is the resource-rich side; 1.x parity).
        val clientClasses = linkedMapOf<String, ClassNode>()
        val serverClasses = linkedMapOf<String, ClassNode>()
        // raw entry bytes alongside the parsed node — needed so
        // byte-identical classes can be emitted verbatim (the
        // canonical MCP-era merge never rewrites an entry whose
        // sides agree; joined binpatches checksum those bytes)
        val clientClassBytes = linkedMapOf<String, ByteArray>()
        val serverClassBytes = linkedMapOf<String, ByteArray>()
        val clientResources = linkedMapOf<String, ByteArray>()
        val serverResources = linkedMapOf<String, ByteArray>()

        fun read(jar: Path, serverSide: Boolean) {
            ZipInputStream(jar.inputStream()).use { zin ->
                var e = zin.nextEntry
                while (e != null) {
                    // META-INF is stripped wholesale except service
                    // registrations — signatures/manifests are side-
                    // specific, but `services/` SPI wiring must survive
                    // or embedded providers (SLF4J etc.) can't bind
                    if (e.isDirectory ||
                        (e.name.startsWith("META-INF/") &&
                                !e.name.startsWith("META-INF/services/")) ||
                        !keep(e.name, serverSide)) {
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
                        // parsed as a class — drop any earlier
                        // resource claim; the class wins
                        clientResources.remove(e.name)
                        serverResources.remove(e.name)
                        (if (serverSide) serverClasses else clientClasses)[e.name] = node
                        (if (serverSide) serverClassBytes else clientClassBytes)[e.name] = bytes
                    } else {
                        (if (serverSide) serverResources else clientResources)[e.name] = bytes
                    }
                    e = zin.nextEntry
                }
            }
        }
        read(client, serverSide = false)
        read(server, serverSide = true)

        // one composed chain — user extensions first, then built-ins.
        // first non-null merge result wins; every transform hook runs
        // in order on the produced node (markers stamp provenance a
        // dispatch/rename merger would record the same way)
        val pipeline = CompositeExtension(options.extensions +
                ServiceFileMerger() +
                SideOnlyMarkers(options.sideMarkers) +
                MemberMerger())

        fun mergeClassEntry(name: String, client: ClassNode?, server: ClassNode?): ByteArray {
            val cb = clientClassBytes[name]
            val sb = serverClassBytes[name]
            // byte-identical sides emit verbatim — the canonical
            // merge the binpatch checksums were built against
            // never rewrites an entry both sides agree on
            if (server != null && cb != null && cb.contentEquals(sb)) return cb
            val ctx = MergeExtension.ClassContext(cb, sb)
            val node = pipeline.mergeClass(name, client, server, ctx)
                ?: error("no class merger handled $name")
            pipeline.transformClass(name, node, ctx)
            return ClassWriter(0).also { node.accept(it) }.toByteArray()
        }

        // merge+serialize fans out — ClassMerger is stateless and
        // each pair owns its ClassNodes; `toList()` on an ordered
        // parallel stream preserves encounter order, so the output
        // jar ordering stays deterministic
        val mergedClasses = linkedMapOf<String, ByteArray>()
        clientClasses.entries.toList().parallelStream().map { (name, node) ->
            val serverNode = serverClasses[name]
            name to try {
                mergeClassEntry(name, node, serverNode)
            } catch (e: Exception) {
                throw RuntimeException("Error merging class $name", e)
            }
        }.toList().forEach { (name, bytes) ->
            mergedClasses[name] = bytes
            serverClasses.remove(name)
        }
        serverClasses.entries.toList().parallelStream().map { (name, node) ->
            name to try {
                mergeClassEntry(name, null, node)
            } catch (e: Exception) {
                throw RuntimeException("Error merging class $name", e)
            }
        }.toList().forEach { (name, bytes) -> mergedClasses[name] = bytes }

        val tmp = out.tempSibling()
        try {
            ZipOutputStream(tmp.outputStream()).use { zout ->
            // explicit dir entries — legacy resource enumeration
            // (`Class.getResource("dir/")`) resolves directories only
            // when the zip carries them; the input skips dir entries
            // so they're re-synthesized here from entry paths
            val dirs = java.util.TreeSet<String>()
            fun collectDirs(name: String) {
                var i = name.indexOf('/')
                while (i > 0) {
                    dirs.add(name.substring(0, i + 1))
                    i = name.indexOf('/', i + 1)
                }
            }
            mergedClasses.keys.forEach(::collectDirs)
            clientResources.keys.forEach(::collectDirs)
            serverResources.keys.forEach(::collectDirs)
            for (d in dirs) {
                zout.putNextEntry(java.util.zip.ZipEntry(d))
                zout.closeEntry()
            }
            for ((name, data) in mergedClasses) {
                zout.putNextEntry(java.util.zip.ZipEntry(name))
                zout.write(data)
                zout.closeEntry()
            }
            val resourceNames = LinkedHashSet<String>()
                .apply { addAll(clientResources.keys); addAll(serverResources.keys) }
            for (name in resourceNames) {
                // an entry that parsed as a class on one side but
                // fell back to a resource on the other is already
                // written from mergedClasses — the class wins
                if (name in mergedClasses) continue
                val c = clientResources[name]
                val s = serverResources[name]
                // extension chain, then client-wins as the default
                // non-class conflict policy
                val merged = pipeline.mergeResource(name, c, s) ?: c ?: s!!
                zout.putNextEntry(java.util.zip.ZipEntry(name))
                zout.write(merged)
                zout.closeEntry()
            }
            }
            tmp.moveAtomicTo(out)
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    private fun pathMatcher(glob: String): PathMatcher =
        FileSystems.getDefault().getPathMatcher("glob:$glob")

}
