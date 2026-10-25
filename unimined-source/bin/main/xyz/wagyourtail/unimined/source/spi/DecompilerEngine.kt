package xyz.wagyourtail.unimined.source.spi

import org.gradle.api.tasks.TaskProvider
import xyz.wagyourtail.unimined.api.spi.Supports
import xyz.wagyourtail.unimined.source.task.DecompileTask

/**
 * SPI implemented by each `unimined-decompiler-*` plugin (vineflower
 * default; fernflower, cfr, others as needed) — same host+SPI selection
 * model as remappers and providers.
 */
interface DecompilerEngine : Supports<DecompileSpec> {

    /** Create the decompile task for [spec]. */
    fun createTask(spec: DecompileSpec): TaskProvider<out DecompileTask>

}
