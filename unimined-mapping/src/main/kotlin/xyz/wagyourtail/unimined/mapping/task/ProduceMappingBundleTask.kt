package xyz.wagyourtail.unimined.mapping.task

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import xyz.wagyourtail.unimined.api.mapping.MappingBundle
import xyz.wagyourtail.unimined.api.mapping.SerializedMappingData
import xyz.wagyourtail.unimined.api.mapping.view
import xyz.wagyourtail.unimined.api.util.uniminedLogger
import xyz.wagyourtail.unimined.mapping.MappingTree
import kotlinx.serialization.json.jsonArray
import xyz.wagyourtail.unimined.mapping.internal.MappingResolver

/**
 * Emits resolved mapping data as a serialized bundle file — the
 * configuration-cache-safe input consumed by remap tasks and artifact
 * transforms. Writes the rich view-payload format when a registered
 * [xyz.wagyourtail.unimined.api.mapping.MappingViewSerializer] covers the
 * resolved views, else the row-only [MappingBundle].
 */
@CacheableTask
abstract class ProduceMappingBundleTask : DefaultTask() {

    /** The environment this bundle serves — drives provider context. */
    @get:Input
    abstract val environmentId: Property<String>

    /** Resolved mapping source files (wiring resolves `maven()` sources). */
    @get:InputFiles
    @get:Classpath
    abstract val mappingFiles: ConfigurableFileCollection

    /** JSON manifest of the materialized sources — per-source
     *  `nsMap`/`formatHint`/`namedNamespaces` from the declarations,
     *  so relabeled channels (calamus/feather…) compose the same here
     *  as in the declared-source path. */
    @get:Input
    abstract val sourcesManifest: Property<String>

    /** Source namespace to serialize from. */
    @get:Input
    abstract val sourceNamespace: Property<String>

    /** Target namespace to serialize to. */
    @get:Input
    abstract val targetNamespace: Property<String>

    /** Serialized bundle output. */
    @get:OutputFile
    abstract val bundleFile: RegularFileProperty

    @TaskAction
    fun produce() {
        val mats = kotlinx.serialization.json.Json.parseToJsonElement(
            sourcesManifest.get()
        ).jsonArray.map {
            xyz.wagyourtail.unimined.mapping.internal.MaterializedSourceSpec
                .fromJson(it).materialize()
        }
        val data = MappingResolver.readMaterialized(
            mats,
            MappingResolver.standaloneContext(environmentId.get(), uniminedLogger(logger))
        )
        val path = bundleFile.get().asFile.toPath()
        val written = SerializedMappingData.write(data, path)
        if (written.isNotEmpty()) {
            uniminedLogger(environmentId.get(), logger).subLogger("mappings").lifecycle(
                "wrote serialized bundle ${path.fileName} (views: ${written.joinToString()})"
            )
            return
        }
        // no serializer payload — fall back to a row bundle per
        // SerializedMappingData.write's contract: capture real rows
        // from the MappingTree when present, else emit a bare identity
        // bundle — a view-less MappingData has nothing to rename
        // (conversion envs' view-less official->official resolve)
        val s = sourceNamespace.get(); val t = targetNamespace.get()
        data.view<MappingTree>()?.let {
            MappingBundle.capture(s, t, it).write(path)
        } ?: MappingBundle(listOf(s, t).distinct(), s, t, emptyList()).write(path)
    }

}
