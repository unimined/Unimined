package xyz.wagyourtail.unimined.source.spi

import org.gradle.api.Project
import org.gradle.api.tasks.TaskProvider
import xyz.wagyourtail.unimined.api.spi.Supports
import xyz.wagyourtail.unimined.source.task.DecompileTask

/**
 * SPI implemented by each `unimined-decompiler-*` plugin (vineflower
 * default; fernflower, cfr, others as needed) — same host+SPI selection
 * model as remappers and providers.
 *
 * Implementations are discovered via `ServiceLoader` —
 * `META-INF/services/xyz.wagyourtail.unimined.source.spi.DecompilerEngine`.
 */
interface DecompilerEngine : Supports<DecompileSpec> {

    /**
     * Create the decompile task [name] implementing [spec] on [project].
     * The host sets the produced `outputJar` location on the returned
     * provider.
     */
    fun createTask(
        project: Project,
        name: String,
        spec: DecompileSpec
    ): TaskProvider<out DecompileTask>

    /**
     * Register the artifact transform backing `genModSources`: jars
     * carrying [ModSourcesTransformSpec.fromArtifactType] become sources
     * jars carrying [ModSourcesTransformSpec.toArtifactType]. The mod
     * dependency set is unbounded and resolution-time, so decompilation
     * runs as a per-artifact transform (cacheable, parallel) rather
     * than a task per mod.
     */
    fun registerModSourcesTransform(project: Project, spec: ModSourcesTransformSpec)

}
