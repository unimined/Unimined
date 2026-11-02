package xyz.wagyourtail.unimined.provider.native

import org.gradle.api.Plugin
import org.gradle.api.Project
import xyz.wagyourtail.unimined.mapping.UniminedRepos

/**
 * Plugin id `xyz.wagyourtail.unimined.provider.native`.
 */
class GradlePlugin : Plugin<Project> {

    override fun apply(target: Project) {
        // Mojang-only library artifacts (authlib, brigadier, blocklist,
        // datafixerupper, ...) live on the official libraries repo, not
        // Maven Central — required by every native-provided environment
        UniminedRepos(target).mojangLibraries.ensure()
    }

}
