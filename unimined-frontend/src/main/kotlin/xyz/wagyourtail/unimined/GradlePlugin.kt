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
        target.extensions.create("unimined", UniminedExtension::class.java, target)
    }

    private companion object {
        val DEFAULT_STACK = listOf(
            "xyz.wagyourtail.unimined.remap",
            "xyz.wagyourtail.unimined.mapping.uml",
            "xyz.wagyourtail.unimined.minecraft",
            "xyz.wagyourtail.unimined.transform",
            "xyz.wagyourtail.unimined.provider.native",
            "xyz.wagyourtail.unimined.remapper.tiny",
            "xyz.wagyourtail.unimined.remapper.uml",
            "xyz.wagyourtail.unimined.remapper.access",
            "xyz.wagyourtail.unimined.transform.fabric"
        )
    }

}
