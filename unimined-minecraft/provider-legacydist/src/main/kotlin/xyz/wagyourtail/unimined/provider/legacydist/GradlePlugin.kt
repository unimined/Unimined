package xyz.wagyourtail.unimined.provider.legacydist

import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * Plugin id `xyz.wagyourtail.unimined.provider.legacydist`.
 *
 * Declared-source provider (plan §1100): the env's jar comes from a
 * user-declared file/maven/URL+SHA-256 or an external producer —
 * never an implicit download. Works without `provider-native`:
 * `supports()` claims any spec whose env declares a legacydist
 * source, including non-MC-version jars (BTA/ReIndev overlays,
 * pre-1.8 bukkit, offline builds).
 */
class GradlePlugin : Plugin<Project> {

    override fun apply(target: Project) {
    }

}
