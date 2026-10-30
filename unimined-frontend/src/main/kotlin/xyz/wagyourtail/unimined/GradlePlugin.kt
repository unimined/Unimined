package xyz.wagyourtail.unimined

import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * Plugin id `xyz.wagyourtail.unimined` — the frontend aggregation facade.
 * Applies the default stack (minecraft/remap/transform hosts, native
 * provider, bundled remap engines) and exposes `unimined { }`.
 */
class GradlePlugin : Plugin<Project> {

    override fun apply(target: Project) {
        for (id in DEFAULT_STACK) {
            target.pluginManager.apply(id)
        }
        warnOnLowDaemonHeap(target)
        target.extensions.create("unimined", UniminedExtension::class.java, target)
    }

    /**
     * Remap/pipeline work currently executes inside the Gradle daemon
     * (artifact transforms), and the full Minecraft jar needs more headroom
     * than the default heap. Warn loudly below [MIN_DAEMON_HEAP] — this
     * check goes away once heavy steps move into worker isolation.
     */
    private fun warnOnLowDaemonHeap(target: Project) {
        if (Runtime.getRuntime().maxMemory() < MIN_DAEMON_HEAP) {
            target.logger.warn("")
            target.logger.warn("[Unimined] The Gradle daemon has less than ${MIN_DAEMON_HEAP shr 30}GB of heap.")
            target.logger.warn("[Unimined] Remapping Minecraft and mod artifacts will likely fail with OutOfMemoryError.")
            target.logger.warn("[Unimined] Add to your gradle.properties:")
            target.logger.warn("[Unimined]   org.gradle.jvmargs=-Xmx${MIN_DAEMON_HEAP shr 30}g")
            target.logger.warn("")
        }
    }

    private companion object {
        const val MIN_DAEMON_HEAP = 4L shl 30
        val DEFAULT_STACK = listOf(
            "xyz.wagyourtail.unimined.remap",
            "xyz.wagyourtail.unimined.mapping",
            "xyz.wagyourtail.unimined.mapping.uml",
            "xyz.wagyourtail.unimined.minecraft",
            "xyz.wagyourtail.unimined.transform",
            "xyz.wagyourtail.unimined.provider.native",
            "xyz.wagyourtail.unimined.remapper.tiny",
            "xyz.wagyourtail.unimined.remapper.uml",
            "xyz.wagyourtail.unimined.remapper.access",
            "xyz.wagyourtail.unimined.transform.fabric",
            "xyz.wagyourtail.unimined.transform.jarmod",
            "xyz.wagyourtail.unimined.mod",
            "xyz.wagyourtail.unimined.migrate",
            "xyz.wagyourtail.unimined.runs",
            "xyz.wagyourtail.unimined.decompiler.vineflower"
        )
    }

}
