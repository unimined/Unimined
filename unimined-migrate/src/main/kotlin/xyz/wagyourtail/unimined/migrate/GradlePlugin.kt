package xyz.wagyourtail.unimined.migrate

import org.gradle.api.Plugin
import org.gradle.api.Project
import xyz.wagyourtail.unimined.api.util.uniminedAdopt

/**
 * Plugin id `xyz.wagyourtail.unimined.migrate` — source-migration
 * tooling (the `migrateMappings` workflow of loom/1.x, redesigned per
 * plan.md §6: spec-based target mappings, grafted trees, UML source
 * remapper). Creates the `migrate` extension; the frontend facade
 * re-presents it via `unimined.migrate { }` /
 * `unimined.migrateMappings(...)`.
 */
class GradlePlugin : Plugin<Project> {

    override fun apply(target: Project) {
        target.extensions.create("uniminedMigrate", MigrateExtension::class.java, target)
        target.uniminedAdopt("migrate", MigrateExtension::class.java)
        target.pluginManager.withPlugin("xyz.wagyourtail.unimined") {
            target.uniminedAdopt("migrate", MigrateExtension::class.java)
        }
    }

}
