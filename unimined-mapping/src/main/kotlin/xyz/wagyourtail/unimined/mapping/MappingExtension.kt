package xyz.wagyourtail.unimined.mapping

import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileCollection
import org.gradle.api.logging.Logging
import org.gradle.api.provider.Provider
import org.gradle.api.provider.ProviderFactory
import xyz.wagyourtail.unimined.api.mapping.MappingData
import xyz.wagyourtail.unimined.api.mapping.MappingBundle
import xyz.wagyourtail.unimined.api.mapping.MappingTree
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

    /**
     * Root for materialized builtin-channel documents — the
     * `unimined-mapping` plugin conventions this to
     * `.gradle/unimined/mapping-cache` inside the project.
     */
    abstract val cacheDir: DirectoryProperty

    private val logger = Logging.getLogger(MappingExtension::class.java)

    private fun ctx(
        environmentId: String,
        resolveMaven: Function<String, Path>,
        resolveChannel: BiFunction<String, Map<String, String>, List<Path>>? = null
    ) = RuntimeMappingContext(
        environmentId, logger, cacheDir.orNull?.asFile?.toPath(), resolveMaven::apply,
        resolveChannel?.let { ch -> { c: String, p: Map<String, String> -> ch.apply(c, p) } }
            ?: { channel, _ ->
                throw UnsupportedOperationException(
                    "builtin channel '$channel' is not supported in this context"
                )
            }
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
        resolveChannel: BiFunction<String, Map<String, String>, List<Path>>? = null
    ): List<Path> = MappingResolver.materializeSources(
        spec, ctx(environmentId, resolveMaven, resolveChannel), sourceProviders
    )

    /** Resolve [spec]'s declared sources into environment-level [MappingData]. */
    @JvmOverloads
    fun resolve(
        spec: MappingsSpecImpl,
        environmentId: String,
        resolveMaven: Function<String, Path>,
        resolveChannel: BiFunction<String, Map<String, String>, List<Path>>? = null
    ): MappingData = MappingResolver.resolve(
        spec, ctx(environmentId, resolveMaven, resolveChannel),
        sourceProviders, registry, composers
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
            "mapping provider did not expose a portable MappingTree view"
        )
        MappingBundle.capture(from.get(), to.get(), tree).toJson()
    }

}
