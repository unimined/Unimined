package xyz.wagyourtail.unimined.mapping.sources

import kotlinx.coroutines.runBlocking
import xyz.wagyourtail.commonskt.utils.toHex
import xyz.wagyourtail.unimined.api.mapping.*
import xyz.wagyourtail.unimined.mapping.MutableMappingTree
import xyz.wagyourtail.unimined.mapping.Namespace
import xyz.wagyourtail.unimined.mapping.formats.mcp.v6.MCPv6FieldReader
import xyz.wagyourtail.unimined.mapping.formats.mcp.v6.MCPv6MethodReader
import xyz.wagyourtail.unimined.mapping.formats.mcp.v6.MCPv6PackageReader
import xyz.wagyourtail.unimined.mapping.formats.mcp.v6.MCPv6ParamReader
import xyz.wagyourtail.unimined.mapping.formats.proguard.ProguardReader
import xyz.wagyourtail.unimined.mapping.formats.srg.SrgReader
import xyz.wagyourtail.unimined.mapping.formats.umf.UMFWriter
import xyz.wagyourtail.unimined.mapping.formats.tsrg.TsrgV1Reader
import xyz.wagyourtail.unimined.mapping.formats.tsrg.TsrgV2Reader
import xyz.wagyourtail.unimined.mapping.internal.MinecraftVersions
import xyz.wagyourtail.unimined.mapping.internal.RuntimeMaterializedMappingSource
import xyz.wagyourtail.unimined.mapping.sink.emit
import xyz.wagyourtail.unimined.mapping.sink.nsFiltered
import xyz.wagyourtail.unimined.mapping.tree.impl.fillMissingNames
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * Materializes the provider/builtin mapping channels — `official`,
 * `mojmap`, `searge`, `parchment`. Channels that need layered or
 * reordered namespaces emit a derived UMF document into the
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
        val params = source.params.mapValues { it.value() }
        return when (source.channel) {
            "official" -> officialSource(source, ctx)
            "mojmap" -> mojmapSource(source, ctx)
            "searge" -> seargeSource(source, ctx, params)
            "forgeBuiltinMCP" -> forgeBuiltinMCPSource(source, ctx, params)
            "parchment" -> parchmentSource(source, ctx, params)
            "spigot" -> spigotSource(source, ctx, params)
            else -> throw IllegalStateException(
                "mappings { ${source.channel}() }: unknown builtin channel; " +
                        "expected one of official, mojmap, searge, forgeBuiltinMCP, parchment, spigot"
            )
        }
    }

    private fun channelCacheDir(ctx: MappingContext, channel: String): Path {
        val base = ctx.cacheDir
            ?: throw IllegalStateException("builtin channel '$channel' requires a mapping cache dir")
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
        return digest.digest().toHex().take(12)
    }

    /** `official()` — the identity channel: an empty document whose only
     *  namespace is `official`, i.e. no rename anywhere. */
    private fun officialSource(
        source: BuiltinMappingSource,
        ctx: MappingContext
    ): MaterializedMappingSource {
        val out = writeDerived(channelCacheDir(ctx, "official"), "official.umf") {
            it.append("umf\t1\t3\nofficial\n")
        }
        // `official` is the raw (obf) side — not a named namespace;
        // an official-only tree still resolves it via the last-ns
        // fallback
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
            ?: throw IllegalStateException(
                "mappings { mojmap() }: the environment's provider did not " +
                        "supply an official-mappings download"
            )
        val official = Namespace("official")
        val mojmap = Namespace("mojmap")
        val out = writeDerived(
            channelCacheDir(ctx, "mojmap"), "mojmap-${contentTag(downloaded)}.umf"
        ) { sb ->
            val tree = MutableMappingTree()
            runBlocking {
                ProguardReader.read(
                    Files.readString(downloaded), tree,
                    mapOf("source" to "mojmap", "target" to "official")
                )
            }
            tree.emit(UMFWriter.write(sb::append, false), nsFilter = listOf(official, mojmap))
        }
        return RuntimeMaterializedMappingSource(
            source, listOf(out), null,
            namedNamespaces = setOf("mojmap")
        )
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
            ?: throw IllegalStateException("mappings { searge() }: missing MCPConfig version")
        val zip = ctx.resolveChannel("searge", params).singleOrNull()
            ?: throw IllegalStateException(
                "mappings { searge($version) }: the environment's provider " +
                        "did not resolve the MCPConfig artifact"
            )
        val tsrg = readZipEntry(zip, "config/joined.tsrg")
            ?: throw IllegalStateException("MCPConfig artifact $zip has no config/joined.tsrg")

        val official = Namespace("official")
        val srg = Namespace("srg")
        val searge = Namespace("searge")
        val layered = MinecraftVersions.compare(version.substringBefore('-'), "1.16.5") > 0
        // cache files are written pre-propagation — no inheritance tree to
        // recover descs from — so they must round-trip desc-less member
        // rows; tiny v2 silently drops them (its `f` line requires a desc),
        // UMF does not
        val tsrgV1 = !layered && !tsrg.trimStart().startsWith("tsrg2")

        val out = writeDerived(
            channelCacheDir(ctx, "searge"),
            "searge-$version-${contentTag(zip)}.umf"
        ) { sb ->
            val tree = MutableMappingTree()
            runBlocking {
                if (layered) {
                    val mojmap = ctx.resolveChannel("mojmap", params).singleOrNull()
                        ?: throw IllegalStateException(
                            "mappings { searge($version) }: layering srg names " +
                                    "for $version requires the mojmap channel, which " +
                                    "this environment's provider did not supply"
                        )
                    val mojmapNs = Namespace("mojmap")
                    ProguardReader.read(
                        Files.readString(mojmap), tree,
                        mapOf("source" to "mojmap", "target" to "official")
                    )
                    // tsrg's `id` column carries numeric srg ids, not
                    // names — filter it so it never enters the tree (it
                    // trips init/clinit name invariants on fill/merge)
                    TsrgV2Reader.read(
                        tsrg, tree,
                        tree.nsFiltered(setOf(Namespace("id")), inverted = true),
                        mapOf("obf" to "official")
                    )
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
                        UMFWriter.write(sb::append, false),
                        nsFilter = listOf(official, mojmapNs, searge)
                    )
                } else if (tsrgV1) {
                    // mcpconfig ≤1.12.x ships headerless tsrg v1
                    // (`a FIELD` entries — no `id` column to filter);
                    // UMF carries desc-less field rows that tiny v2 drops
                    TsrgV1Reader.read(
                        tsrg, tree,
                        mapOf("source" to "official", "target" to "searge")
                    )
                    tree.emit(
                        UMFWriter.write(sb::append, false),
                        nsFilter = listOf(official, searge)
                    )
                } else {
                    TsrgV2Reader.read(
                        tsrg, tree,
                        tree.nsFiltered(setOf(Namespace("id")), inverted = true),
                        mapOf("obf" to "official", "srg" to "searge")
                    )
                    tree.emit(
                        UMFWriter.write(sb::append, false),
                        nsFilter = listOf(official, searge)
                    )
                }
            }
        }
        // `searge` is not a user-facing named namespace (1.x flags it
        // `provides(..., false)`): flagging it would make it win the
        // default `devNamespace` pick ahead of yarn/mcp — a
        // searge-only tree still resolves it via the last-namespace
        // fallback
        return RuntimeMaterializedMappingSource(
            source, listOf(out), null,
            namedNamespaces = emptySet()
        )
    }

    /**
     * `forgeBuiltinMCP(version)` — FG1-era forge ships its own MCP
     * data inside the `:src` zip (`forge/fml/conf/`): `joined.srg`
     * carries `official -> searge`, the csvs layer
     * `searge -> mcp` member/package/param names on top. Port of 1.x
     * `MappingsProvider.forgeBuiltinMCP`: the mcp namespace is
     * relabeled `forgeMCP` and flagged named (the dev namespace);
     * `searge` stays intermediate.
     */
    private fun forgeBuiltinMCPSource(
        source: BuiltinMappingSource,
        ctx: MappingContext,
        params: Map<String, String>
    ): MaterializedMappingSource {
        val version = params["version"]
            ?: throw IllegalStateException("mappings { forgeBuiltinMCP() }: missing forge version")
        val zip = ctx.resolveChannel("forgeBuiltinMCP", params).singleOrNull()
            ?: throw IllegalStateException(
                "mappings { forgeBuiltinMCP($version) }: the environment's " +
                        "provider did not resolve the forge src artifact"
            )

        val official = Namespace("official")
        val searge = Namespace("searge")
        val forgeMCP = Namespace("forgeMCP")
        val confNs = mapOf("mcp" to "forgeMCP")

        val out = writeDerived(
            channelCacheDir(ctx, "forgeBuiltinMCP"),
            "forgeBuiltinMCP-$version-${contentTag(zip)}.umf"
        ) { sb ->
            val tree = MutableMappingTree()
            ZipFile(zip.toFile()).use { file ->
                val conf = file.entries().asSequence()
                    .filter { !it.isDirectory && it.name.endsWith("/conf/" + it.name.substringAfterLast('/')) }
                    .associateBy { it.name.substringAfterLast('/') }
                fun entry(name: String) =
                    conf[name]?.let { file.getInputStream(it).bufferedReader().readText() }

                runBlocking {
                    val srg = entry("joined.srg") ?: throw IllegalStateException(
                        "forge src artifact $zip has no forge/fml/conf/joined.srg"
                    )
                    SrgReader.read(
                        srg, tree,
                        mapOf("source" to "official", "target" to "searge")
                    )
                    // csv readers are context formats — they attach
                    // searge-keyed names onto the srg-populated tree
                    entry("fields.csv")?.let {
                        MCPv6FieldReader.read(it, tree, confNs)
                    }
                    entry("methods.csv")?.let {
                        MCPv6MethodReader.read(it, tree, confNs)
                    }
                    entry("params.csv")?.let {
                        MCPv6ParamReader.read(it, tree, confNs)
                    }
                    entry("packages.csv")?.let {
                        MCPv6PackageReader.read(it, tree, confNs)
                    }
                }
            }
            tree.emit(
                UMFWriter.write(sb::append, false),
                // env-aware csv emit lands sided rows under `ns:env` —
                // keep the env columns or those members lose their names
                nsFilter = listOf(
                    official, searge, forgeMCP,
                    searge.forEnv("client"), searge.forEnv("server"),
                    forgeMCP.forEnv("client"), forgeMCP.forEnv("server")
                )
            )
        }
        return RuntimeMaterializedMappingSource(
            source, listOf(out), null,
            namedNamespaces = setOf("forgeMCP")
        )
    }

    /**
     * `parchment(mcVersion, version)` — mojmap-keyed parameter names and
     * javadoc. The document carries a single `source` namespace; it is
     * relabeled `mojmap` so it attaches to the mojmap hop in the chain.
     */

    /**
     * `spigot(version)` — Spigot BuildData class+member mappings as the
     * named `spigot` namespace. The `spigot` channel resolves to the
     * builddata archive zip; `info.json` names the `classMappings` /
     * `memberMappings` entries (SRG). Classes and members both land
     * under `spigot`; `spigotProd` (versioned-NMS relocation, members
     * still obfuscated) is derived downstream by the bukkit transform.
     */
    private fun spigotSource(
        source: BuiltinMappingSource,
        ctx: MappingContext,
        params: Map<String, String>
    ): MaterializedMappingSource {
        val version = params["version"]
            ?: throw IllegalStateException("mappings { spigot() }: missing version")
        val zip = ctx.resolveChannel("spigot", params).singleOrNull()
            ?: throw IllegalStateException(
                "mappings { spigot($version) }: the environment's provider " +
                        "did not resolve the Spigot BuildData archive"
            )
        val info = readZipEntry(zip, "info.json")
            ?: throw IllegalStateException("BuildData archive $zip has no info.json")
        val classMap = Regex(""""classMappings"\s*:\s*"([^"]+)"""")
            .find(info)?.groupValues?.get(1)
            ?: throw IllegalStateException("BuildData $zip info.json has no classMappings")
        // mojmap-era BuildData (>=1.17-ish) carries no memberMappings —
        // members come from a mojmap source composed downstream (1.x's
        // layerMojmap/hasMembers fork), so it's optional here
        val memberMap = Regex(""""memberMappings"\s*:\s*"([^"]+)"""")
            .find(info)?.groupValues?.get(1)

        val official = Namespace("official")
        val spigot = Namespace("spigot")
        val out = writeDerived(
            channelCacheDir(ctx, "spigot"),
            "spigot-$version-${contentTag(zip)}.umf"
        ) { sb ->
            val tree = MutableMappingTree()
            runBlocking {
                for (name in listOfNotNull(classMap, memberMap)) {
                    val doc = readZipEntry(zip, name)
                        ?: throw IllegalStateException(
                            "BuildData $zip missing mapping file $name")
                    SrgReader.read(doc, tree,
                        mapOf("left" to "official", "right" to "spigot"))
                }
            }
            tree.emit(UMFWriter.write(sb::append, false),
                nsFilter = listOf(official, spigot))
        }
        return RuntimeMaterializedMappingSource(
            source, listOf(out), null,
            namedNamespaces = setOf("spigot")
        )
    }

    private fun parchmentSource(
        source: BuiltinMappingSource,
        ctx: MappingContext,
        params: Map<String, String>
    ): MaterializedMappingSource {
        val zip = ctx.resolveChannel("parchment", params).singleOrNull()
            ?: throw IllegalStateException(
                "mappings { parchment() }: the environment's provider did " +
                        "not resolve the parchment artifact"
            )
        val json = extractEntry(
            zip, "parchment.json",
            channelCacheDir(ctx, "parchment"),
            "parchment-${params["version"] ?: "unknown"}-${contentTag(zip)}.json"
        ) ?: throw IllegalStateException("parchment artifact $zip has no parchment.json")
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
