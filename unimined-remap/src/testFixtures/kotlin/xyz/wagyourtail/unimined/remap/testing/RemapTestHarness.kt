package xyz.wagyourtail.unimined.remap.testing

import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import xyz.wagyourtail.commons.core.logger.JavaLogWrapper
import xyz.wagyourtail.commons.core.logger.Logger
import org.objectweb.asm.ClassReader
import org.objectweb.asm.tree.ClassNode
import xyz.wagyourtail.commonskt.utils.toHex
import xyz.wagyourtail.unimined.api.access.AccessModel
import xyz.wagyourtail.unimined.api.mapping.MappingData
import xyz.wagyourtail.unimined.api.spec.RemapSpec
import xyz.wagyourtail.unimined.mapping.MutableMappingTree
import xyz.wagyourtail.unimined.mapping.formats.srg.SrgReader
import xyz.wagyourtail.unimined.remap.spi.PipelineState
import xyz.wagyourtail.unimined.remap.spi.StepContext
import java.nio.file.Files
import java.nio.file.Paths
import java.security.MessageDigest
import java.util.zip.ZipFile
import kotlin.io.path.exists
import kotlin.io.path.readBytes

/**
 * No-Gradle harness for pipeline matrix tests: module-cache +
 * provider-cache artifact location with download-on-miss, srg→tree
 * reading, fake [StepContext] construction, and jar class inspection.
 * Engine plugins drive their own steps against the fakes here.
 */
object RemapTestHarness {

    private val files21 = Paths.get(
        System.getProperty("user.home"), ".gradle/caches/modules-2/files-2.1")

    /** Test-local artifact store — download-on-miss keeps matrices
     *  working on fresh checkouts where the Gradle caches are cold.
     *  Resolves per-consumer-project `build/matrix-artifacts`. */
    private val testStore = Paths.get("build/matrix-artifacts")

    fun download(url: String, dest: Path): Path? = runCatching {
        Files.createDirectories(dest.parent)
        java.net.URI(url).toURL().openStream().use {
            Files.copy(it, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
        dest
    }.getOrNull()

    /** Module-cache hit, else maven.wagyourtail.xyz download.
     *  [fileMatch] is the filename tail, e.g. `-client.zip`. */
    fun artifact(group: String, name: String, version: String,
                 fileMatch: String): Path? {
        val d = files21.resolve("$group/$name/$version")
        if (d.exists()) Files.walk(d).use { s ->
            s.filter { it.fileName.toString().endsWith(fileMatch) }
                .filter { !it.fileName.toString().contains("sources") }
                .findFirst().orElse(null)
        }?.let { return it }
        val file = "$name-$version$fileMatch"
        return download(
            "https://maven.wagyourtail.xyz/releases/" +
                "${group.replace('.', '/')}/$name/$version/$file",
            testStore.resolve(file))
    }

    /** Unimined's provider cache first; on a cold cache resolve the
     *  official jar via the version manifest into the test store. */
    fun mcJar(version: String, side: String): Path? {
        Paths.get(System.getProperty("user.home"),
            ".gradle/caches/unimined/native-cache/versions/$version/$side.jar")
            .takeIf { it.exists() }?.let { return it }
        val dest = testStore.resolve("$version-$side.jar")
        if (dest.exists()) return dest
        // manifest/vjson are flat enough for positional extraction —
        // find the marker, then the "url": "<value>" following it
        fun String.urlAfter(marker: String, window: Int = 2000): String? {
            val i = indexOf(marker)
            if (i < 0) return null
            val seg = substring(i, minOf(i + window, length))
            val u = seg.indexOf("\"url\": \"")
            return if (u < 0) null else
                seg.substring(u + 8).substringBefore('"')
        }
        val manifest = runCatching {
            java.net.URI("https://launchermeta.mojang.com/mc/game/version_manifest.json")
                .toURL().readText() }.getOrNull() ?: return null
        val vUrl = manifest.urlAfter("\"id\": \"$version\"") ?: return null
        val vJson = runCatching {
            java.net.URI(vUrl).toURL().readText() }.getOrNull() ?: return null
        val jarUrl = vJson.urlAfter("\"$side\":") ?: return null
        return download(jarUrl, dest)
    }

    fun sha1(p: Path): String = MessageDigest.getInstance("SHA-1")
        .digest(p.readBytes()).toHex()

    /** mcp_legacy zip -> tree for [side]'s srg (the pipeline's
     *  filterSideDocs picks one side's docs — never both). `source`/
     *  `target` relabeled to `official`/`searge`, mirroring mcp().
     *  @return tree, or null when the bundle has no `<side>.srg`
     *  (pre-srg csv-only eras). */
    fun srgTree(mcpZip: Path, side: String, extractTo: Path): MutableMappingTree? =
        runBlocking {
            val out = extractTo.resolve("mcp-${mcpZip.fileName}")
            Files.createDirectories(out)
            ZipFile(mcpZip.toFile()).use { z ->
                z.entries().asSequence().filter { !it.isDirectory }.forEach { e ->
                    val p = out.resolve(e.name)
                    Files.createDirectories(p.parent)
                    z.getInputStream(e).use { Files.copy(it, p) }
                }
            }
            val tree = MutableMappingTree()
            val f = out.resolve("conf/$side.srg")
            if (!f.exists()) return@runBlocking null
            SrgReader.read(Files.readString(f), tree,
                mapOf("source" to "official", "target" to "searge"))
            tree
        }

    /** Fake [StepContext] for engine steps — source `official`,
     *  target [targetNs] (`searge` covers class-name assertions). */
    fun remapCtx(data: MappingData, classpath: List<Path>, workDir: Path,
                 sourceNs: String = "official", targetNs: String = "searge") =
        object : StepContext {
            override val state: PipelineState by lazy {
                PipelineState(spec.sourceNamespace, null) }
            override val spec = object : RemapSpec {
                override val sourceNamespace = sourceNs
                override val targetNamespace = targetNs
                override val classpath: List<Path> = emptyList()
            }
            override val mappings = data
            override val classpath = classpath
            override val accessModel: AccessModel? = null
            override val workDir = workDir
            override val logger: Logger = JavaLogWrapper(java.util.logging.Logger.getLogger("matrix"))
        }

    /** Read a class from a jar — null when absent. */
    fun classNode(jar: Path, internal: String): ClassNode? =
        ZipFile(jar.toFile()).use { z ->
            z.getEntry("$internal.class")?.let { e ->
                z.getInputStream(e).use { bytes ->
                    ClassNode().also { ClassReader(bytes.readBytes()).accept(it, 0) }
                }
            }
        }
}
