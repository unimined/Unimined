package xyz.wagyourtail.unimined.migrate

import org.gradle.api.Action
import org.gradle.api.Project
import org.gradle.api.tasks.SourceSetContainer
import xyz.wagyourtail.unimined.mapping.MappingExtension
import xyz.wagyourtail.unimined.minecraft.MinecraftExtension
import xyz.wagyourtail.unimined.migrate.task.MigrateMappingsTask
import java.nio.file.Path
import java.util.function.Function
import javax.inject.Inject

/**
 * The `migrate` extension — migration/validation utilities contributed
 * by `unimined-migrate` (see plan.md §6).
 *
 * Task registration is on-demand like 1.x: `migrateMappings` only
 * exists after this method is called, so no mapping-spec resolution
 * happens in projects that never migrate.
 */
abstract class MigrateExtension @Inject constructor(
    private val project: Project
) {

    /**
     * Registers `migrateMappings` (`migrateMappings<Ss>` for non-main)
     * to migrate [environmentId]'s sources onto a different mappings
     * release — configure the target inside [action] via
     * `target { yarn(...) }` / `target { mcp(...) }` / `target { maven(...) }`.
     */
    fun migrateMappings(environmentId: String, action: Action<MigrateMappingsTask>) {
        val minecraft = project.extensions.getByType(MinecraftExtension::class.java)
        val mapping = project.extensions.getByType(MappingExtension::class.java)
        val sourceSet = project.extensions.getByType(SourceSetContainer::class.java)
            .getByName(environmentId)
        val name = "migrateMappings" + if (environmentId == "main") ""
            else environmentId.replaceFirstChar(Char::uppercase)
        // registers its own produceMappingBundle task — must happen
        // outside the tasks.register below (nested registration is
        // illegal)
        val inputBundle = minecraft.mappingBundle(
            environmentId, "migrate",
            minecraft.devNamespace(environmentId),
            minecraft.modSourceNamespaces(environmentId).map { it.first() }
        )
        project.tasks.register(name, MigrateMappingsTask::class.java) { t ->
            t.group = "unimined"
            t.description = "Migrates '${sourceSet.name}' sources onto different mappings"
            t.environmentId.set(environmentId)
            // serialized bundle carries the full resolved tree —
            // the file is the config-cache-safe input
            t.inputBundleFile.set(inputBundle)
            t.devNamespace.set(minecraft.devNamespace(environmentId))
            t.prodNamespaces.set(minecraft.modSourceNamespaces(environmentId))
            t.targetMcVersion.set(
                minecraft.environments.getByName(environmentId).version
            )
            t.sourceDirs.from(sourceSet.allSource)
            t.remapClasspath.from(sourceSet.compileClasspath)
            t.outputDir.set(project.layout.projectDirectory.dir("remappedSrc"))
            t.globalCacheDir.set(project.layout.dir(project.provider {
                xyz.wagyourtail.unimined.api.util.UniminedCache
                    .global(project).resolve("mapping-cache").toFile()
            }))
        }.configure { t ->
            action.execute(t)
            // materialize the spec `target {}` populated — the provider
            // resolves to FILES at store/execution time, so the
            // non-serializable spec object never hits the config cache
            val spec = t.targetSpec ?: error(
                "unimined.migrateMappings: configure the target via `target { ... }`"
            )
            val mavenResolver = Function<String, Path> { notation ->
                project.configurations.detachedConfiguration(
                    project.dependencies.create(notation)
                ).apply { isTransitive = false }.files.single().toPath()
            }
            t.targetMappingFiles.from(project.providers.provider {
                mapping.materializeSources(
                    spec, "$environmentId-migrate", mavenResolver
                )
            })
            t.targetClasspath.from(project.providers.provider {
                t.classpathNotations.get().map(mavenResolver::apply)
            })
        }
    }

}
