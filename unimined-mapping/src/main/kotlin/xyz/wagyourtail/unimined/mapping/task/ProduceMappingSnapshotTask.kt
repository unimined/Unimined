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
import xyz.wagyourtail.unimined.api.mapping.MappingSnapshot
import xyz.wagyourtail.unimined.api.mapping.MappingTree
import xyz.wagyourtail.unimined.api.mapping.view
import xyz.wagyourtail.unimined.mapping.internal.MappingResolver

/**
 * Emits resolved mapping data as a serialized [MappingSnapshot] file — the
 * configuration-cache-safe input consumed by remap tasks and artifact
 * transforms.
 */
@CacheableTask
abstract class ProduceMappingSnapshotTask : DefaultTask() {

    /** The environment this snapshot serves — drives provider context. */
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

    /** Serialized snapshot output. */
    @get:OutputFile
    abstract val snapshotFile: RegularFileProperty

    @TaskAction
    fun produce() {
        val data = MappingResolver.readFiles(
            mappingFiles.files.map { it.toPath() },
            MappingResolver.standaloneContext(environmentId.get(), logger)
        )
        val tree = data.view<MappingTree>() ?: error(
            "mapping provider did not expose a portable MappingTree view"
        )
        val snapshot = MappingSnapshot.capture(
            sourceNamespace.get(), targetNamespace.get(), tree
        )
        snapshot.write(snapshotFile.get().asFile.toPath())
    }

}
