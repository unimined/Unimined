package xyz.wagyourtail.unimined.provider.native

import org.gradle.api.Plugin
import org.gradle.api.Project
import xyz.wagyourtail.unimined.mapping.UniminedRepos

/**
 * Plugin id `xyz.wagyourtail.unimined.provider.native`.
 */
class GradlePlugin : Plugin<Project> {

    override fun apply(target: Project) {
        // Maven Central must precede Mojang's host (1.x order): Gradle
        // pins a module's artifacts to the repo that served its
        // metadata, and libraries.minecraft.net hosts POMs + main jars
        // for modern shared deps (netty) while missing their platform
        // classifiers — central-first keeps those resolvable.
        // Mojang-only library artifacts (authlib, brigadier, blocklist,
        // datafixerupper, ...) live on the official libraries repo, not
        // Maven Central — required by every native-provided environment
        val repos = UniminedRepos(target)
        repos.mavenCentral.ensure()
        repos.mojangLibraries.ensure()
    }

}
