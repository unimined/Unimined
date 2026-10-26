package xyz.wagyourtail.unimined.mapping.sources

import kotlinx.coroutines.runBlocking
import org.gradle.api.GradleException
import xyz.wagyourtail.unimined.api.mapping.BuiltinMappingSource
import xyz.wagyourtail.unimined.api.mapping.MappingContext
import xyz.wagyourtail.unimined.api.mapping.MappingSource
import xyz.wagyourtail.unimined.api.mapping.MappingSourceProvider
import xyz.wagyourtail.unimined.api.mapping.MaterializedMappingSource
import xyz.wagyourtail.unimined.mapping.MutableMappingTree
import xyz.wagyourtail.unimined.mapping.Namespace
import xyz.wagyourtail.unimined.mapping.formats.proguard.ProguardReader
import xyz.wagyourtail.unimined.mapping.formats.tiny.v2.TinyV2Writer
import xyz.wagyourtail.unimined.mapping.formats.tsrg.TsrgV2Reader
import xyz.wagyourtail.unimined.mapping.internal.MinecraftVersions
import xyz.wagyourtail.unimined.mapping.internal.RuntimeMaterializedMappingSource
import xyz.wagyourtail.unimined.mapping.sink.emit
import xyz.wagyourtail.unimined.mapping.tree.impl.fillMissingNames
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * Materializes the provider/builtin mapping channels — `official`,
 * `mojmap`, `searge`, `parchment`. Channels that need layered or
 * reordered namespaces emit a derived tiny-v2 document into the
 * environment cache rather than exposing raw downloads: the derived doc
 * keeps the canonical `official`-first ordering the composer expects.
 *
 * Channel artifacts resolve through [MappingContext.resolveChannel],
 * implemented by the environment host (provider manifest downloads for
 * `mojmap`, repository wiring for `searge`/`parchment`).
 */
class BuiltinMappingSourceProvider : MappingSourceProvider {

    override val name = "builtin"

    override fun supports(spec: MappingSource): Boolean = spec is BuiltinMappingSource

    override fun materialize(
        source: MappingSource,
        ctx: MappingContext
    ): MaterializedMappingSource {
        source as BuiltinMappingSource
        val params = source.params.mapValues { it.value.get() }
        return when (source.channel) {
            "official" -> officialSource(source, ctx)
            "mojmap" -> mojmapSource(source, ctx)
            "searge" -> seargeSource(source, ctx, params)
            "parchment" -> parchmentSource(source, ctx, params)
            else -> throw GradleException(
                "mappings { ${source.channel}() }: unknown builtin channel; " +
                    "expected one of official, mojmap, searge, parchment"
            )
        }
    }

    private fun channelCacheDir(ctx: MappingContext, channel: String): Path {
        val base = ctx.cacheDir
            ?: throw GradleException("builtin channel '$channel' requires a mapping cache dir")
        return base.resolve("builtin/$channel/${ctx.environmentId}")
    }

    private fun writeDerived(dir: Path, name: String, write: (StringBuilder) -> Unit): Path {
        val out = dir.resolve(name)
        if (!Files.exists(out)) {
            val sb = StringBuilder()
            write(sb)
            Files.createDirectories(dir)
            Files.writeString(out, sb)
        }
        return out
    }

    /**
     * Short content tag for derived-document filenames — derived docs
     * cache by name, so the tag keeps stale outputs from being served
     * after the underlying channel artifact changes.
     */
    private fun contentTag(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-1")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }.take(12)
    }

    /** `official()` — the identity channel: an empty document whose only
     *  namespace is `official`, i.e. no rename anywhere. */
    private fun officialSource(
        source: BuiltinMappingSource,
        ctx: MappingContext
    ): MaterializedMappingSource {
        val out = writeDerived(channelCacheDir(ctx, "official"), "official.tiny") {
            it.append("tiny\t2\t0\tofficial\n")
        }
        return RuntimeMaterializedMappingSource(source, listOf(out), null)
    }

    /**
     * `mojmap()` — Mojang's proguard mappings. The document reads
     * `named -> obf` (named is its src), so it is re-emitted as
     * `official -> mojmap` to keep the source namespace first.
     */
    private fun mojmapSource(
        source: BuiltinMappingSource,
        ctx: MappingContext
    ): MaterializedMappingSource {
        val downloaded = ctx.resolveChannel("mojmap", emptyMap()).singleOrNull()
            ?: throw GradleException(
                "mappings { mojmap() }: the environment's provider did not " +
                    "supply an official-mappings download"
            )
        val official = Namespace("official")
        val mojmap = Namespace("mojmap")
        val out = writeDerived(
            channelCacheDir(ctx, "mojmap"), "mojmap-${contentTag(downloaded)}.tiny"
        ) { sb ->
            val tree = MutableMappingTree()
            runBlocking {
                ProguardReader.read(
                    Files.readString(downloaded), tree,
                    mapOf("source" to "mojmap", "target" to "official")
                )
            }
            tree.emit(TinyV2Writer.write(sb::append), nsFilter = listOf(official, mojmap))
        }
        return RuntimeMaterializedMappingSource(source, listOf(out), null)
    }

    /**
     * `searge(version)` — MCPConfig. For Minecraft releases after 1.16.5
     * the `searge` namespace layers srg member names onto mojmap class
     * names (1.x `postProcessDependency` semantics): srg class names would
     * split the codebase's own packages, so classes inherit the mojmap
     * name and only members keep the srg name. Older releases carry the
     * plain `obf -> srg` document relabeled `official -> searge`.
     */
    private fun seargeSource(
        source: BuiltinMappingSource,
        ctx: MappingContext,
        params: Map<String, String>
    ): MaterializedMappingSource {
        val version = params["version"]
            ?: throw GradleException("mappings { searge() }: missing MCPConfig version")
        val zip = ctx.resolveChannel("searge", params).singleOrNull()
            ?: throw GradleException(
                "mappings { searge($version) }: the environment's provider " +
                    "did not resolve the MCPConfig artifact"
            )
        val tsrg = readZipEntry(zip, "config/joined.tsrg")
            ?: throw GradleException("MCPConfig artifact $zip has no config/joined.tsrg")

        val official = Namespace("official")
        val srg = Namespace("srg")
        val searge = Namespace("searge")
        val layered = MinecraftVersions.compare(version.substringBefore('-'), "1.16.5") > 0

        val out = writeDerived(
            channelCacheDir(ctx, "searge"),
            "searge-$version-${contentTag(zip)}.tiny"
        ) { sb ->
            val tree = MutableMappingTree()
            runBlocking {
                if (layered) {
                    val mojmap = ctx.resolveChannel("mojmap", params).singleOrNull()
                        ?: throw GradleException(
                            "mappings { searge($version) }: layering srg names " +
                                "for $version requires the mojmap channel, which " +
                                "this environment's provider did not supply"
                        )
                    val mojmapNs = Namespace("mojmap")
                    ProguardReader.read(
                        Files.readString(mojmap), tree,
                        mapOf("source" to "mojmap", "target" to "official")
                    )
                    TsrgV2Reader.read(tsrg, tree, mapOf("obf" to "official"))
                    // classes take the mojmap name so srg class names can't
                    // scatter the tree across MCP-style packages; members
                    // keep srg names via the fill below
                    for (cls in tree.classes.toList()) {
                        val off = cls.names[official] ?: continue
                        val moj = cls.names[mojmapNs] ?: continue
                        // clazz() registers the searge namespace and merges
                        // the name onto the class matched by its official name
                        tree.clazz(mapOf(official to off, searge to moj))
                    }
                    tree.fillMissingNames(srg to setOf(searge))
                    tree.emit(
                        TinyV2Writer.write(sb::append),
                        nsFilter = listOf(official, mojmapNs, searge)
                    )
                } else {
                    TsrgV2Reader.read(
                        tsrg, tree,
                        mapOf("obf" to "official", "srg" to "searge")
                    )
                    tree.emit(
                        TinyV2Writer.write(sb::append),
                        nsFilter = listOf(official, searge)
                    )
                }
            }
        }
        return RuntimeMaterializedMappingSource(source, listOf(out), null)
    }

    /**
     * `parchment(mcVersion, version)` — mojmap-keyed parameter names and
     * javadoc. The document carries a single `source` namespace; it is
     * relabeled `mojmap` so it attaches to the mojmap hop in the chain.
     */
    private fun parchmentSource(
        source: BuiltinMappingSource,
        ctx: MappingContext,
        params: Map<String, String>
    ): MaterializedMappingSource {
        val zip = ctx.resolveChannel("parchment", params).singleOrNull()
            ?: throw GradleException(
                "mappings { parchment() }: the environment's provider did " +
                    "not resolve the parchment artifact"
            )
        val json = extractEntry(
            zip, "parchment.json",
            channelCacheDir(ctx, "parchment"),
            "parchment-${params["version"] ?: "unknown"}-${contentTag(zip)}.json"
        ) ?: throw GradleException("parchment artifact $zip has no parchment.json")
        return RuntimeMaterializedMappingSource(
            source, listOf(json), "parchment",
            mapOf("source" to "mojmap") + source.nsMap
        )
    }

    private fun readZipEntry(zip: Path, name: String): String? =
        ZipFile(zip.toFile()).use { file ->
            file.getEntry(name)?.let { file.getInputStream(it).bufferedReader().readText() }
        }

    private fun extractEntry(zip: Path, name: String, dir: Path, outName: String): Path? {
        ZipFile(zip.toFile()).use { file ->
            val entry = file.getEntry(name) ?: return null
            val out = dir.resolve(outName)
            if (!Files.exists(out)) {
                Files.createDirectories(dir)
                file.getInputStream(entry).use { Files.copy(it, out) }
            }
            return out
        }
    }

}
