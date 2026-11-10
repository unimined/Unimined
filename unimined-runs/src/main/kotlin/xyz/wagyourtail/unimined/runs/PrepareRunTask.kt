package xyz.wagyourtail.unimined.runs

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import org.gradle.api.tasks.Classpath

/**
 * Stages the run directory before a [MinecraftRunTask] executes — loader
 * envs contribute staging via `prepareTaskMutators` (dyn-lib copies,
 * mod-dir seeding, metadata files). Named task so staging is visible in
 * the task graph instead of buried in the run task's `doFirst`.
 */
@DisableCachingByDefault(because = "run-dir staging is fast and env-local")
abstract class PrepareRunTask : DefaultTask() {

    /** The run directory this task materializes (mirrors the run task's). */
    @get:OutputDirectory
    abstract val runDirectory: DirectoryProperty

    /**
     * Mod jars to stage into the run directory — the project's own
     * jar (dev: `jar`; prod: `remapJar`) plus the run-namespace mod
     * views. Copied into `runDir/<stageInto>` by [prepare].
     */
    @get:Classpath
    @get:InputFiles
    abstract val modFiles: org.gradle.api.file.ConfigurableFileCollection

    /**
     * Subdirectory under the run dir that receives [modFiles] —
     * loaders that consume a mod folder (FoxLoader's `mods/`) opt in
     * via `prepareTaskMutators`. Unset = no staging.
     */
    @get:Input
    @get:org.gradle.api.tasks.Optional
    abstract val stageInto: org.gradle.api.provider.Property<String>

    /**
     * Dynamic-library jars staged into [dynLibInto] — a separate
     * channel from [modFiles] (FML-era loaders fetch libs from
     * `runDir/lib`, not the mod folder). File names may be rewritten
     * through [dynLibRenames].
     */
    @get:Classpath
    @get:InputFiles
    abstract val dynLibFiles: org.gradle.api.file.ConfigurableFileCollection

    /** Subdirectory receiving [dynLibFiles]. Unset = no staging. */
    @get:Input
    @get:org.gradle.api.tasks.Optional
    abstract val dynLibInto: org.gradle.api.provider.Property<String>

    /** `originalName -> stagedName` for [dynLibFiles] (empty = verbatim). */
    @get:Input
    abstract val dynLibRenames: org.gradle.api.provider.MapProperty<String, String>

    init {
        group = "unimined"
        dynLibRenames.convention(emptyMap())
    }

    @TaskAction
    fun prepare() {
        val dir = runDirectory.get().asFile.apply { mkdirs() }
        stageInto.orNull?.let { sub ->
            val target = dir.resolve(sub).apply { mkdirs() }
            modFiles.filter { it.isFile }.forEach {
                it.copyTo(target.resolve(it.name), overwrite = true)
            }
        }
        dynLibInto.orNull?.let { sub ->
            val renames = dynLibRenames.get()
            val target = dir.resolve(sub).apply { mkdirs() }
            dynLibFiles.filter { it.isFile }.forEach {
                it.copyTo(
                    target.resolve(renames[it.name] ?: it.name),
                    overwrite = true
                )
            }
        }
    }

}
