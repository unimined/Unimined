package xyz.wagyourtail.unimined.source.task

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.*
import xyz.wagyourtail.unimined.minecraft.localrepo.LocalMavenPublisher
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.io.path.createDirectories

/**
 * `genModSources`/`gen<Env>ModSources`: publishes decompiled mod
 * dependency sources into the generated local maven repo, under each
 * module's **original** `group:artifact:version` as the `sources`
 * classifier.
 *
 * Remapped mod dependencies keep their original component identity
 * (the dev-namespace jars arrive via artifact transforms), so the
 * IDE's sources-classifier lookup — `g/a/v/a-v-sources.jar`, resolved
 * through artifact-level repository fallthrough — attaches these files
 * without any coordinate remapping.
 *
 * No POM is written for mod coordinates: a metadata-bearing module in
 * the generated repo could shadow the real module's descriptor
 * (dependencies would resolve from our minimal POM). The repo only
 * serves the sources artifact file.
 *
 * Decompilation itself runs in the artifact transform chain registered
 * by `GenModSourcesWiring` — this task only resolves the views and
 * copies the results into maven layout.
 */
@CacheableTask
abstract class GenModSourcesTask : DefaultTask() {

    /** The resolved mod-sources artifact views (decompiled jars). */
    @get:InputFiles
    @get:Classpath
    abstract val sourceJars: ConfigurableFileCollection

    /** Module coordinates each sources jar publishes under. */
    @get:Input
    abstract val mods: ListProperty<ModSourceSpec>

    /** The generated local maven repo (`unimined.generated` stubs live
     *  here; mod coordinates are file-only, no descriptor). */
    @get:Internal
    abstract val localRepoDir: DirectoryProperty

    @get:OutputFiles
    abstract val publishedSources: ListProperty<RegularFile>

    init {
        val outputs: org.gradle.api.provider.Provider<List<RegularFile>> =
            localRepoDir.zip(mods) { repo, specs ->
                specs.map { mod ->
                    repo.file(
                        "${mod.group.replace('.', '/')}/${mod.artifact}/${mod.version}/" +
                                "${mod.artifact}-${mod.version}-sources.jar"
                    )
                }
            }
        publishedSources.set(outputs)
    }

    @TaskAction
    fun publish() {
        val jarsByName = sourceJars.files.groupBy { it.name }
        val published = mutableSetOf<String>()
        for (mod in mods.get()) {
            val gav = "${mod.group}:${mod.artifact}:${mod.version}"
            if (!published.add(gav)) continue
            val candidates = jarsByName[mod.fileName]
                ?: error(
                    "genModSources: no sources jar named '${mod.fileName}' for $gav " +
                            "in the resolved mod-sources view"
                )
            val jar = candidates.singleOrNull() ?: error(
                "genModSources: ${candidates.size} artifacts named '${mod.fileName}' " +
                        "in the mod-sources view — cannot pick the sources jar for $gav"
            )
            val moduleDir = LocalMavenPublisher.moduleDir(
                localRepoDir.get().asFile.toPath(), mod.group, mod.artifact, mod.version
            )
            moduleDir.createDirectories()
            Files.copy(
                jar.toPath(),
                moduleDir.resolve("${mod.artifact}-${mod.version}-sources.jar"),
                StandardCopyOption.REPLACE_EXISTING
            )
            logger.info("genModSources: published sources for $gav")
        }
    }

}
