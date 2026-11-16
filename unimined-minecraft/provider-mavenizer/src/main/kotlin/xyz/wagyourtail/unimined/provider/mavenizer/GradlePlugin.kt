package xyz.wagyourtail.unimined.provider.mavenizer

import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * Plugin id `xyz.wagyourtail.unimined.provider.mavenizer`.
 *
 * Reserved for the FG6-style mavenizer product provider (installing MC
 * artifacts into a real Maven repository layout). The provider itself is
 * not implemented yet — applying the plugin fails rather than silently
 * producing an environment with no artifact production.
 */
class GradlePlugin : Plugin<Project> {

    override fun apply(target: Project) {
        throw GradleException(
            "xyz.wagyourtail.unimined.provider.mavenizer is a reserved plugin id: " +
                "the mavenizer product provider is not implemented yet " +
                "(see Docs/plan-gradle-parity.md). Remove the plugin id to continue."
        )
    }

}
