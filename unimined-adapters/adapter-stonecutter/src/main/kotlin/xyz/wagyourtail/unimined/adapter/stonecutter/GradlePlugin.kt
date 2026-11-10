package xyz.wagyourtail.unimined.adapter.stonecutter

import dev.kikugie.stonecutter.build.StonecutterBuildExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import xyz.wagyourtail.unimined.minecraft.MinecraftExtension

/**
 * Optional Stonecutter bridge — when `dev.kikugie.stonecutter`'s
 * build-side plugin is present, every environment's `version`
 * conventions to the chiseled project's version
 * (`stonecutter.current.version`), so a shared per-version build
 * script doesn't repeat `version = stonecutter.current.version`
 * in every target. An explicit `version` always wins (convention,
 * not override).
 *
 * Requires Java 21 — Stonecutter's own floor (it publishes only a
 * v65 shadow-jar variant); the adapter is dead weight without it.
 *
 * Environment binding/run wiring follows the normal env model —
 * each chiseled project is one env-bound source set already.
 */
class GradlePlugin : Plugin<Project> {

    override fun apply(project: Project) {
        project.pluginManager.withPlugin("dev.kikugie.stonecutter") {
            val sc = project.extensions.findByType(
                StonecutterBuildExtension::class.java)
            if (sc == null) {
                project.logger.warn(
                    "[Unimined/Stonecutter] 'dev.kikugie.stonecutter' " +
                    "applied but no 'stonecutter' extension found — " +
                    "skipping")
                return@withPlugin
            }
            // key on the minecraft plugin (not the umbrella id) so
            // modular consumers are covered too
            project.pluginManager.withPlugin(
                "xyz.wagyourtail.unimined.minecraft") {
                wireFromStonecutter(project, sc)
            }
        }
    }

}

/** Convention wiring — separated from plugin-application for
 *  testability (the extension is an interface, trivially faked). */
internal fun wireFromStonecutter(project: Project,
                                 sc: StonecutterBuildExtension) {
    val version = sc.current.version
    val minecraft = project.extensions.getByType(MinecraftExtension::class.java)
    minecraft.environments.configureEach { env ->
        env.version.convention(version)
    }
}
