package xyz.wagyourtail.unimined.mapping

import xyz.wagyourtail.unimined.api.util.uniminedLogger
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileCollection
import org.gradle.api.logging.Logging
import org.gradle.api.provider.Provider
import org.gradle.api.provider.ProviderFactory
import xyz.wagyourtail.unimined.api.mapping.MappingBundle
import xyz.wagyourtail.unimined.api.mapping.MappingData
import xyz.wagyourtail.unimined.api.mapping.MaterializedMappingSource
import xyz.wagyourtail.unimined.api.mapping.view
import xyz.wagyourtail.unimined.mapping.internal.MappingResolver
import xyz.wagyourtail.unimined.mapping.internal.RuntimeMappingContext
import java.nio.file.Path
import java.util.function.BiFunction
import java.util.function.Function
import javax.inject.Inject

/** Extension installed by the `unimined-mapping` host plugin. */
abstract class MappingExtension @Inject constructor(
    private val providers: ProviderFactory
) {

    /** Source materializers — builtin channels plus ServiceLoader'd customs. */
    val sourceProviders = MappingSourceProviderRegistry()

    /** Mapping providers discovered from the plugin classpath. */
    val registry = MappingProviderRegistry()

    /** Composers merging per-source MappingData — DefaultMappingComposer fallback. */
    val composers = MappingComposerRegistry()

    /** Ordered post-processors applied to composed data before consumers. */
    val postProcessors = MappingPostProcessorRegistry()

    /**
     * Root for materialized builtin-channel documents — the
     * `unimined-mapping` plugin conventions this to
     * `.gradle/unimined/mapping-cache` inside the project.
     */
    abstract val cacheDir: DirectoryProperty

    /** Global merged-mapping cache root (content-keyed; empty disables). */
    abstract val globalCacheDir: DirectoryProperty

    private val logger = Logging.getLogger(MappingExtension::class.java)

    private fun ctx(
        environmentId: String,
        resolveMaven: Function<String, Path>,
        resolveChannel: BiFunction<String, Map<String, String>, List<Path>>? = null,
        envSide: xyz.wagyourtail.unimined.api.spec.Side? = null
    ) = RuntimeMappingContext(
        environmentId, uniminedLogger(logger), cacheDir.orNull?.asFile?.toPath(),
        globalCacheDir.orNull?.asFile?.toPath(), resolveMaven::apply,
        resolveChannel?.let { ch -> { c: String, p: Map<String, String> -> ch.apply(c, p) } }
            ?: { channel, _ ->
                throw UnsupportedOperationException(
                    "builtin channel '$channel' is not supported in this context"
                )
            },
        envSide
    )

    /**
     * Materialize all effective sources in [spec] to local mapping files.
     * Maven sources resolve through [resolveMaven]; builtin/provider
     * channels resolve through [resolveChannel].
     */
    @JvmOverloads
    fun materializeSources(
        spec: MappingsSpecImpl,
        environmentId: String,
        resolveMaven: Function<String, Path>,
        resolveChannel: BiFunction<String, Map<String, String>, List<Path>>? = null,
        envSide: xyz.wagyourtail.unimined.api.spec.Side? = null
    ): List<MaterializedMappingSource> = MappingResolver.materializeSources(
        spec, ctx(environmentId, resolveMaven, resolveChannel, envSide), sourceProviders
    )

    /** Resolve [spec]'s declared sources into environment-level [MappingData]. */
    @JvmOverloads
    fun resolve(
        spec: MappingsSpecImpl,
        environmentId: String,
        resolveMaven: Function<String, Path>,
        resolveChannel: BiFunction<String, Map<String, String>, List<Path>>? = null,
        envSide: xyz.wagyourtail.unimined.api.spec.Side? = null
    ): MappingData = MappingResolver.resolve(
        spec, ctx(environmentId, resolveMaven, resolveChannel, envSide),
        sourceProviders, registry, composers, postProcessors
    )

    /**
     * Read already-materialized mapping files through the provider +
     * composer pipeline — for consumers holding files, not declarations.
     */
    fun readFiles(files: List<Path>, environmentId: String): MappingData =
        MappingResolver.readFiles(
            files, MappingResolver.standaloneContext(
                environmentId, uniminedLogger(logger),
                globalCacheDir.orNull?.asFile?.toPath()
            ),
            registry, composers, postProcessors
        )

    /**
     * Lazily computes the serialized [MappingBundle] from [files] for
     * `from -> to`. The mapping materialization and composition run inside
     * the provider's evaluation — this exists because artifact-transform
     * parameters cannot carry task dependencies, so a task-produced
     * bundle file would never exist when the transform executes.
     */
    fun bundleJson(
        environmentId: String,
        files: FileCollection,
        from: Provider<String>,
        to: Provider<String>
    ): Provider<String> = providers.provider {
        val data = readFiles(files.files.map { it.toPath() }, environmentId)
        val tree = data.view<MappingTree>() ?: error(
            "mapping provider did not expose a UML MappingTree view"
        )
        MappingBundle.capture(from.get(), to.get(), tree).toJson()
    }

}
