package xyz.wagyourtail.unimined.provider.buildtools

import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * Plugin id `xyz.wagyourtail.unimined.provider.buildtools` —
 * contributes [BuildToolsProvider] through the `ProductProvider`
 * ServiceLoader; nothing to wire at apply time.
 */
class GradlePlugin : Plugin<Project> {

    override fun apply(target: Project) {
    }

}
