package xyz.wagyourtail.unimined.mapping

import org.gradle.api.logging.Logging
import xyz.wagyourtail.unimined.api.mapping.MappingData
import xyz.wagyourtail.unimined.mapping.internal.MappingResolver
import xyz.wagyourtail.unimined.mapping.internal.RuntimeMappingContext
import java.nio.file.Path
import java.util.function.Function

/** Extension installed by the `unimined-mapping` host plugin. */
abstract class MappingExtension {

    /** Source materializers — builtin channels plus ServiceLoader'd customs. */
    val sourceProviders = MappingSourceProviderRegistry()

    /** Mapping providers discovered from the plugin classpath. */
    val registry = MappingProviderRegistry()

    /** Composers merging per-source MappingData — DefaultMappingComposer fallback. */
    val composers = MappingComposerRegistry()

    private val logger = Logging.getLogger(MappingExtension::class.java)

    private fun ctx(
        environmentId: String,
        resolveMaven: Function<String, Path>
    ) = RuntimeMappingContext(environmentId, logger, null, resolveMaven::apply)

    /**
     * Materialize all effective sources in [spec] to local mapping files.
     * Maven and builtin sources are resolved through [resolveMaven].
     */
    fun materializeSources(
        spec: MappingsSpecImpl,
        environmentId: String,
        resolveMaven: Function<String, Path>
    ): List<Path> = MappingResolver.materializeSources(
        spec, ctx(environmentId, resolveMaven), sourceProviders
    )

    /** Resolve [spec]'s declared sources into environment-level [MappingData]. */
    fun resolve(
        spec: MappingsSpecImpl,
        environmentId: String,
        resolveMaven: Function<String, Path>
    ): MappingData = MappingResolver.resolve(
        spec, ctx(environmentId, resolveMaven), sourceProviders, registry, composers
    )

    /**
     * Read already-materialized mapping files through the provider +
     * composer pipeline — for consumers holding files, not declarations.
     */
    fun readFiles(files: List<Path>, environmentId: String): MappingData =
        MappingResolver.readFiles(
            files, MappingResolver.standaloneContext(environmentId, logger),
            registry, composers
        )

}
