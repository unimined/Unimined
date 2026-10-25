package xyz.wagyourtail.unimined.provider.native

import org.gradle.api.Plugin
import org.gradle.api.Project
import java.net.URI

/**
 * Plugin id `xyz.wagyourtail.unimined.provider.native`.
 */
class GradlePlugin : Plugin<Project> {

    override fun apply(target: Project) {
        // Mojang-only library artifacts (authlib, brigadier, blocklist,
        // datafixerupper, ...) live on the official libraries repo, not
        // Maven Central — required by every native-provided environment
        target.repositories.maven { repo ->
            repo.name = "Mojang Libraries"
            repo.url = URI.create("https://libraries.minecraft.net")
        }
    }

}
