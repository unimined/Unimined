package xyz.wagyourtail.unimined.mapping.internal

import org.gradle.api.logging.Logger
import xyz.wagyourtail.unimined.api.mapping.*
import xyz.wagyourtail.unimined.api.util.uniminedLogger
import xyz.wagyourtail.unimined.mapping.*
import java.nio.file.Path
import xyz.wagyourtail.unimined.api.mapping.FileMappingSource
import xyz.wagyourtail.unimined.api.mapping.MaterializedMappingSource
import xyz.wagyourtail.unimined.api.mapping.MappingSource
import xyz.wagyourtail.unimined.api.mapping.SerializedMappingData
import xyz.wagyourtail.unimined.api.spec.Side
import java.security.MessageDigest
import java.nio.file.Files

/**
 * The mapping resolution pipeline: materialize each declared source
 * through a [MappingSourceProviderRegistry]-selected source provider,
 * read each materialized source through a selected [MappingProviderRegistry]
 * provider, then merge the ordered parts through a selected
 * [MappingComposerRegistry] composer. Composed data then runs through
 * the [MappingPostProcessorRegistry] stages before being returned.
 */
internal object MappingResolver {

    /**
     * Drops the wrong side's `client.srg`/`server.srg` era document —
     * `mcp_legacy` zips ship BOTH, and pre-1.3 client/server obf
     * namespaces are independent, so composing the wrong side's rows
     * renames classes through a foreign namespace (same obf name,
     * different target) and permutes class contents. Only fires when
     * the pair is actually present; `null`/`MERGED` keeps everything.
     */
    private fun filterSideDocs(files: List<Path>, side: Side?): List<Path> {
        if (side == null || side == Side.MERGED) return files
        val other = if (side == Side.CLIENT) "server" else "client"
        val names = files.map { it.fileName.toString() }
        if (!names.any { it.equals("client.srg", true) } ||
            !names.any { it.equals("server.srg", true) }
        ) return files
        return files.filter { !it.fileName.toString().equals("$other.srg", true) }
    }

    /** Resolve [spec]'s declared sources into environment-level [MappingData]. */
    fun resolve(
        spec: MappingsSpecImpl,
        ctx: MappingContext,
        sourceProviders: MappingSourceProviderRegistry = MappingSourceProviderRegistry(),
        providers: MappingProviderRegistry = MappingProviderRegistry(),
        composers: MappingComposerRegistry = MappingComposerRegistry(),
        postProcessors: MappingPostProcessorRegistry = MappingPostProcessorRegistry()
    ): MappingData {
        val sources = spec.effectiveSources()
        val slog = uniminedLogger(ctx.environmentId, ctx.logger).subLogger("mappings")
        slog.lifecycle(
            "resolving mappings (${sources.size} sources)"
        )
        // named namespaces, in declaration order — each source's flagged
        // names plus the `"named"` document-column convention (1.x's
        // `outputs(ns, named)` tracking). The environment's dev namespace
        // defaults to the first entry present in the composed tree.
        val named = LinkedHashSet<String>()
        // materialize first — the materialized file set keys the
        // merged-output cache, and cheap materialization is shared by
        // hit and miss paths alike
        val mats = sources.mapNotNull { src ->
            var m = sourceProviders.select(src).materialize(src, ctx)
            val filtered = filterSideDocs(m.files, ctx.envSide)
            if (filtered !== m.files) {
                m = RuntimeMaterializedMappingSource(
                    m.declaration, filtered, m.formatHint,
                    m.nsMap, m.namedNamespaces, m.attachments
                )
            }
            if (m.files.isEmpty()) {
                // lazily-empty declarations (e.g. a conditional loader hop)
                // contribute no part — reading them would emit a tree with
                // no namespaces
                slog.debug(
                    "source $src materialized no files; skipping"
                )
                return@mapNotNull null
            }
            named += m.namedNamespaces
            src to m
        }
        val cacheFile = mergedCacheFile(ctx, sources, mats)
        val cachedData = cacheFile?.takeIf { Files.exists(it) }?.let { f ->
            runCatching {
                SerializedMappingData.readEither(f).also {
                    slog.lifecycle("merged mappings cache hit (${f.fileName})")
                }
            }.onFailure {
                slog.info("merged mappings cache read failed: ${it.message}")
            }.getOrNull()
        }
        var data = cachedData ?: run {
            val parts = mats.map { (_, m) -> providers.select(m).resolve(m, ctx) }
            val request = MappingComposeRequest(parts)
            composers.select(request).compose(request, ctx).also { composed ->
                cacheFile?.let { f ->
                    runCatching {
                        Files.createDirectories(f.parent)
                        SerializedMappingData.write(composed, f)
                    }.onFailure {
                        slog.info("merged mappings cache write failed: ${it.message}")
                    }
                }
            }
        }
        data.view<MappingTree>()?.namespaces
            ?.filter { it.name == "named" }
            ?.forEach { named += it.name }
        data = postProcessors.process(data, ctx)
        if (named.isNotEmpty()) {
            data = object : MappingData by data {
                override val properties = data.properties +
                        (NAMED_NAMESPACES_KEY to named.joinToString(","))
            }
        }
        // spec-scoped enrichments (transform wiring — e.g. jar-processor
        // mapping mutations) run after the global registry
        for (postProcessor in spec.postProcessors) {
            data = postProcessor.process(data, ctx)
        }
        spec.namespaces = data.view<MappingTree>()?.namespaces?.map { it.name }
            ?: emptyList()
        return data
    }

    /** Materialize all effective sources in [spec] to local mapping files. */
    fun materializeSources(
        spec: MappingsSpecImpl,
        ctx: MappingContext,
        sourceProviders: MappingSourceProviderRegistry = MappingSourceProviderRegistry()
    ): List<Path> = spec.effectiveSources().flatMap { src ->
        filterSideDocs(
            sourceProviders.select(src).materialize(src, ctx).files,
            ctx.envSide
        )
    }

    /**
     * Read pre-materialized files through the provider + composer pipeline —
     * the task/transform path, where declarations no longer exist.
     */
    fun readFiles(
        files: List<Path>,
        ctx: MappingContext,
        providers: MappingProviderRegistry = MappingProviderRegistry(),
        composers: MappingComposerRegistry = MappingComposerRegistry(),
        postProcessors: MappingPostProcessorRegistry = MappingPostProcessorRegistry()
    ): MappingData {
        val slog = uniminedLogger(ctx.environmentId, ctx.logger).subLogger("mappings")
        val files = filterSideDocs(files, ctx.envSide)
        val cacheFile = mergedCacheFile(ctx, files)
        var data = cacheFile?.takeIf { Files.exists(it) }?.let { f ->
            runCatching {
                SerializedMappingData.readEither(f).also {
                    slog.lifecycle("merged mappings cache hit (${f.fileName})")
                }
            }.onFailure {
                slog.info("merged mappings cache read failed: ${it.message}")
            }.getOrNull()
        }
        if (data == null) {
            val parts = files.map { file ->
                val t0 = System.nanoTime()
                val materialized = RuntimeMaterializedMappingSource(
                    RawFileSource(file), listOf(file), null
                )
                providers.select(materialized).resolve(materialized, ctx).also {
                    slog.lifecycle(
                        "resolved ${file.fileName} in ${(System.nanoTime() - t0) / 1_000_000}ms"
                    )
                }
            }
            val request = MappingComposeRequest(parts)
            val t1 = System.nanoTime()
            data = composers.select(request).compose(request, ctx).also { composed ->
                cacheFile?.let { f ->
                    runCatching {
                        Files.createDirectories(f.parent)
                        SerializedMappingData.write(composed, f)
                    }.onFailure {
                        slog.info("merged mappings cache write failed: ${it.message}")
                    }
                }
                slog.lifecycle(
                    "composed ${parts.size} parts in ${(System.nanoTime() - t1) / 1_000_000}ms"
                )
            }
        }
        return postProcessors.process(data, ctx)
    }

    /** Build a standalone context for file-only consumers (tasks). */
    /**
     * Global merged-output cache file for this spec, or `null` when the
     * spec is not globally cacheable. Key = sha1 over each materialized
     * file's content plus per-source parse hints — project-file
     * ("stub") inputs land in isolated keys by content, so they are
     * safe to cache rather than needing 1.x's local-only carve-out.
     */
    private fun mergedCacheFile(
        ctx: MappingContext,
        sources: List<MappingSource>,
        mats: List<Pair<MappingSource, MaterializedMappingSource>>
    ): Path? {
        val root = ctx.globalCacheDir ?: return null.also {
            uniminedLogger(ctx.environmentId, ctx.logger)
                .subLogger("mappings")
                .info("merged-cache disabled: no globalCacheDir")
        }
        // Content-keying subsumes 1.x's "no stubs -> global" rule: a
        // project file's hash differs across projects, so stubbed specs
        // naturally get isolated keys while identical ones legitimately
        // share. Unhashable (missing/lazy) files just disable the cache.
        val md = MessageDigest.getInstance("SHA-1")
        fun put(s: String) = md.update(s.toByteArray(Charsets.UTF_8))
        put("unimined-merged-v3")
        for ((_, m) in mats) {
            put(m.formatHint ?: "")
            put(m.nsMap.toString())
            put(m.namedNamespaces.sorted().toString())
            for (f in m.files) {
                put(f.fileName.toString())
                val bytes = runCatching { Files.readAllBytes(f) }
                    .getOrNull() ?: return null
                md.update(bytes)
            }
        }
        return root.resolve("mappings-merged").resolve(
            md.digest().joinToString("") { "%02x".format(it) } + ".json"
        )
    }

    /** File-keyed variant — the spec-less `readFiles` path. */
    private fun mergedCacheFile(
        ctx: MappingContext, files: List<Path>
    ): Path? {
        val root = ctx.globalCacheDir ?: return null
        val md = MessageDigest.getInstance("SHA-1")
        fun put(s: String) = md.update(s.toByteArray(Charsets.UTF_8))
        put("unimined-readfiles-v2")
        for (f in files) {
            put(f.fileName.toString())
            val bytes = runCatching { Files.readAllBytes(f) }
                .getOrNull() ?: return null
            md.update(bytes)
        }
        return root.resolve("mappings-merged").resolve(
            md.digest().joinToString("") { "%02x".format(it) } + ".json"
        )
    }

    fun standaloneContext(
        environmentId: String, logger: Logger, globalCacheDir: Path? = null,
        envSide: Side? = null
    ): MappingContext =
        RuntimeMappingContext(environmentId, logger, null, globalCacheDir, envSide = envSide, mavenResolver = { notation ->
            throw UnsupportedOperationException(
                "maven resolution is not available here; materialize '$notation' " +
                        "through the environment's mapping spec first"
            )
        })

    /** Synthetic declaration for files materialized outside the DSL. */
    private class RawFileSource(private val file: Path) : MappingSource {
        override fun toString() = "file:$file"
    }

}
