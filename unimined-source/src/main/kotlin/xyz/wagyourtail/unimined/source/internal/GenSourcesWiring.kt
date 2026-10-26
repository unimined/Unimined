package xyz.wagyourtail.unimined.source.internal

import org.gradle.api.Project
import org.gradle.api.file.FileCollection
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.SourceSet
import xyz.wagyourtail.unimined.minecraft.MinecraftEnvironment
import xyz.wagyourtail.unimined.minecraft.MinecraftExtension
import xyz.wagyourtail.unimined.minecraft.localrepo.LocalMavenPublisher
import xyz.wagyourtail.unimined.remap.RemapExtension
import xyz.wagyourtail.unimined.remap.spi.RemapSurface
import xyz.wagyourtail.unimined.remap.task.RemapJarTask
import xyz.wagyourtail.unimined.source.DecompilerRegistry
import xyz.wagyourtail.unimined.source.SourceExtension
import xyz.wagyourtail.unimined.source.spi.DecompileSpec

/**
 * Wires one bound environment's `genSources`/`gen<Env>Sources` task.
 *
 * The decompiler input is the environment's dev-namespace jar — forked
 * through the [RemapSurface.DECOMPILE] pipeline when that surface
 * declares steps, so decompile-quality transforms never touch the
 * runtime artifact. The classpath is the environment's resolved
 * Minecraft libraries. Engine selection is ServiceLoader-discovered
 * plus the `source.engine` DSL override.
 */
internal object GenSourcesWiring {

    fun wire(
        project: Project,
        environment: MinecraftEnvironment,
        @Suppress("unused") sourceSet: SourceSet
    ) {
        project.afterEvaluate {
            wireGenSources(project, environment)
        }
    }

    private fun wireGenSources(project: Project, environment: MinecraftEnvironment) {
        val minecraft = project.extensions.getByType(MinecraftExtension::class.java)
        val remap = project.extensions.getByType(RemapExtension::class.java)
        val source = project.extensions.getByType(SourceExtension::class.java)
        val environmentId = environment.environmentId

        val devView = project.configurations.getByName(
            environment.minecraftDevViewName
        )
        val libraries = project.configurations.getByName(
            environment.minecraftLibrariesConfigurationName
        )

        val input = decompileInput(project, minecraft, remap, environmentId, devView)

        val taskName = if (environmentId == "main") {
            "genSources"
        } else {
            "gen${environmentId.cap()}Sources"
        }

        val spec = object : DecompileSpec {
            override val input: Provider<RegularFile> = input
            override val classpath: FileCollection = libraries
            override val options: Map<String, String>
                get() = source.options.get()
        }

        val engine = DecompilerRegistry().select(spec, source.engine.orNull)
        val task = engine.createTask(project, taskName, spec)
        task.configure { t ->
            t.group = "unimined"
            t.description = "Decompiles the '$environmentId' Minecraft artifact into a sources jar"
            // write straight into the generated module's sources slot —
            // IDEA attaches the sources classifier on sync without the
            // sync path ever triggering a decompile
            val artifactId = "minecraft-$environmentId"
            val version = environment.version.get()
            t.outputJar.set(
                LocalMavenPublisher.moduleDir(
                    minecraft.localRepoDir, "unimined.generated",
                    artifactId, version
                ).resolve("$artifactId-$version-sources.jar").toFile()
            )
        }
    }

    /**
     * The dev-namespace jar — forked through the DECOMPILE-surface
     * pipeline when that surface declares steps. Reads the step list at
     * configuration time (after evaluation, so the user's `remap {}`
     * declarations are final), matching `defaultRemapJar`'s model.
     */
    private fun decompileInput(
        project: Project,
        minecraft: MinecraftExtension,
        remap: RemapExtension,
        environmentId: String,
        devView: org.gradle.api.artifacts.Configuration
    ): Provider<RegularFile> {
        val devJar = project.layout.file(
            project.provider { devView.singleFile }
        )
        val steps = remap.pipelineSteps(environmentId, RemapSurface.DECOMPILE).get()
        if (steps.isEmpty()) return devJar

        val devNamespace = minecraft.devNamespace(environmentId)
        val fork = project.tasks.register(
            "decompileSurface${environmentId.cap()}",
            RemapJarTask::class.java
        ) { t ->
            t.group = "unimined"
            t.description =
                "Decompile-surface fork of the '$environmentId' dev artifact"
            t.inputArtifact.set(devJar)
            t.surface.set(RemapSurface.DECOMPILE)
            t.stepSpecs.set(remap.pipelineSteps(environmentId, RemapSurface.DECOMPILE))
            t.sourceNamespace.set(devNamespace)
            t.targetNamespace.set(devNamespace)
            t.mappingFile.set(
                minecraft.mappingBundle(
                    environmentId, "decompile-$environmentId", devNamespace, devNamespace
                )
            )
            t.outputArtifact.set(
                project.layout.buildDirectory.file(
                    "unimined/sources/minecraft-$environmentId-decompile.jar"
                )
            )
        }
        return fork.flatMap { it.outputArtifact }
    }

    private fun String.cap(): String = replaceFirstChar(Char::uppercase)

}
