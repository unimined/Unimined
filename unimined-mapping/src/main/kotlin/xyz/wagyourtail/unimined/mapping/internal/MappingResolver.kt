package xyz.wagyourtail.unimined.mapping.internal

import org.gradle.api.logging.Logger
import xyz.wagyourtail.unimined.api.mapping.MappingComposeRequest
import xyz.wagyourtail.unimined.api.mapping.MappingContext
import xyz.wagyourtail.unimined.api.mapping.MappingData
import xyz.wagyourtail.unimined.api.mapping.MappingSource
import xyz.wagyourtail.unimined.api.mapping.MappingTree
import xyz.wagyourtail.unimined.api.mapping.view
import xyz.wagyourtail.unimined.mapping.MappingComposerRegistry
import xyz.wagyourtail.unimined.mapping.MappingProviderRegistry
import xyz.wagyourtail.unimined.mapping.MappingSourceProviderRegistry
import xyz.wagyourtail.unimined.mapping.MappingsSpecImpl
import java.nio.file.Path

/**
 * The mapping resolution pipeline: materialize each declared source
 * through a [MappingSourceProviderRegistry]-selected source provider,
 * read each materialized source through a selected [MappingProviderRegistry]
 * provider, then merge the ordered parts through a selected
 * [MappingComposerRegistry] composer.
 */
internal object MappingResolver {

    /** Resolve [spec]'s declared sources into environment-level [MappingData]. */
    fun resolve(
        spec: MappingsSpecImpl,
        ctx: MappingContext,
        sourceProviders: MappingSourceProviderRegistry = MappingSourceProviderRegistry(),
        providers: MappingProviderRegistry = MappingProviderRegistry(),
        composers: MappingComposerRegistry = MappingComposerRegistry()
    ): MappingData {
        val parts = spec.effectiveSources().mapNotNull { src ->
            val materialized = sourceProviders.select(src).materialize(src, ctx)
            if (materialized.files.isEmpty()) {
                // lazily-empty declarations (e.g. a conditional loader hop)
                // contribute no part — reading them would emit a tree with
                // no namespaces
                ctx.logger.debug(
                    "[Unimined/Mappings] source $src materialized no files; skipping"
                )
                return@mapNotNull null
            }
            providers.select(materialized).resolve(materialized, ctx)
        }
        val request = MappingComposeRequest(parts)
        val data = composers.select(request).compose(request, ctx)
        spec.namespaces = data.view<MappingTree>()?.namespaces ?: emptyList()
        return data
    }

    /** Materialize all effective sources in [spec] to local mapping files. */
    fun materializeSources(
        spec: MappingsSpecImpl,
        ctx: MappingContext,
        sourceProviders: MappingSourceProviderRegistry = MappingSourceProviderRegistry()
    ): List<Path> = spec.effectiveSources().flatMap { src ->
        sourceProviders.select(src).materialize(src, ctx).files
    }

    /**
     * Read pre-materialized files through the provider + composer pipeline —
     * the task/transform path, where declarations no longer exist.
     */
    fun readFiles(
        files: List<Path>,
        ctx: MappingContext,
        providers: MappingProviderRegistry = MappingProviderRegistry(),
        composers: MappingComposerRegistry = MappingComposerRegistry()
    ): MappingData {
        val parts = files.map { file ->
            val materialized = RuntimeMaterializedMappingSource(
                RawFileSource(file), listOf(file), null
            )
            providers.select(materialized).resolve(materialized, ctx)
        }
        val request = MappingComposeRequest(parts)
        return composers.select(request).compose(request, ctx)
    }

    /** Build a standalone context for file-only consumers (tasks). */
    fun standaloneContext(environmentId: String, logger: Logger): MappingContext =
        RuntimeMappingContext(environmentId, logger, null, { notation ->
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
