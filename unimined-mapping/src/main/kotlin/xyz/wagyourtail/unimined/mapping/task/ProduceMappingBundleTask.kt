package xyz.wagyourtail.unimined.mapping.task

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import xyz.wagyourtail.unimined.api.mapping.MappingBundle
import xyz.wagyourtail.unimined.api.mapping.MappingTree
import xyz.wagyourtail.unimined.api.mapping.SerializedMappingData
import xyz.wagyourtail.unimined.api.mapping.view
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
        val data = MappingResolver.readFiles(
            mappingFiles.files.map { it.toPath() },
            MappingResolver.standaloneContext(environmentId.get(), logger)
        )
        val path = bundleFile.get().asFile.toPath()
        val written = SerializedMappingData.write(data, path)
        if (written.isNotEmpty()) {
            logger.lifecycle(
                "[Unimined/MappingBundle] wrote serialized bundle {} (views: {})",
                path.fileName, written.joinToString()
            )
            return
        }
        val tree = data.view<MappingTree>() ?: error(
            "mapping provider exposed no serializable views and no portable MappingTree view"
        )
        MappingBundle.capture(
            sourceNamespace.get(), targetNamespace.get(), tree
        ).write(path)
    }

}
