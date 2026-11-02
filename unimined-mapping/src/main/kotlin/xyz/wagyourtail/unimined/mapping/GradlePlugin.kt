package xyz.wagyourtail.unimined.mapping

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.initialization.Settings
import org.gradle.api.plugins.ExtensionAware
import org.gradle.api.plugins.PluginAware
import xyz.wagyourtail.unimined.api.util.UniminedCache

/**
 * Plugin ids `xyz.wagyourtail.unimined.mapping` and
 * `xyz.wagyourtail.unimined.mapping.settings` — mapping SPI host for
 * source materialization, provider selection, composition, and
 * serialized mapping bundle tasks.
 *
 * The target is [PluginAware] so the same class serves both levels:
 * applied to a [Project] it creates the `mapping` extension; applied
 * to [Settings] it skips that (no source sets there) — and either way
 * it registers a `uniminedRepos` extension bound to the target's
 * repositories (settings → `dependencyResolutionManagement`), so
 * `uniminedRepos.fabric.ensure()` works in both script kinds.
 */
class GradlePlugin : Plugin<PluginAware> {

    override fun apply(target: PluginAware) {
        // find-or-register — the companion owns extension creation and
        // dedupes on findByName, so repeat applications (plugin id AND
        // pluginManager.apply(Class) reach apply() through different
        // classloaders, which Gradle does not dedupe) are no-ops
        UniminedRepos(target)
        if (target is Project) {
            val extension = target.extensions.create("mapping", MappingExtension::class.java)
            extension.cacheDir.convention(
                target.layout.projectDirectory.dir(".gradle/unimined/mapping-cache")
            )
            extension.globalCacheDir.convention(
                target.layout.dir(target.provider {
                    UniminedCache.global(target).resolve("mapping-cache").toFile()
                })
            )
        }
    }

}
