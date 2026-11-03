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
import xyz.wagyourtail.unimined.remap.RemapStepSpec
import xyz.wagyourtail.unimined.remap.spi.RemapSurface
import xyz.wagyourtail.unimined.remap.spi.StepSpec
import xyz.wagyourtail.unimined.remap.task.RemapArtifactTask
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

        // write straight into the generated module's sources slot —
        // IDEA attaches the sources classifier on sync without the
        // sync path ever triggering a decompile
        val artifactId = "minecraft-$environmentId"
        val version = environment.version.get()
        val repoSlot = LocalMavenPublisher.moduleDir(
            minecraft.localRepoDir, "unimined.generated",
            artifactId, version
        ).resolve("$artifactId-$version-sources.jar").toFile()

        val devSources = environment.devSources

        val taskName = if (environmentId == "main") {
            "genSources"
        } else {
            "gen${environmentId.cap()}Sources"
        }

        // the generated sources feed the SOURCES-surface pipeline (source
        // remap, unpick, javadoc) before landing in the repo slot
        fun wireSourcesSurface(
            input: Provider<RegularFile>,
            inputNamespace: Provider<String>
        ) {
            val devNamespace = minecraft.devNamespace(environmentId)
            // registers the bundle producer eagerly — calling it inside
            // the task action would register during task realization,
            // an illegal context (breaks `tasks`, task-graph calc)
            val mappingFile = minecraft.mappingBundle(
                environmentId, "sources-$environmentId", inputNamespace, devNamespace
            )
            project.tasks.register(
                "sourcesSurface${environmentId.cap()}",
                RemapArtifactTask::class.java
            ) { t ->
                t.group = "unimined"
                t.description =
                    "Sources-surface pipeline for the '$environmentId' decompiled sources"
                t.inputArtifact.set(input)
                t.surface.set(RemapSurface.SOURCES)
                // a transform-provided sources jar still needs its
                // prodNs→devNs remap — prepend an implicit remap core
                // like pipelineSteps does for empty JAR surfaces
                t.stepSpecs.set(remap.pipelineSteps(environmentId, RemapSurface.SOURCES).map {
                    if (inputNamespace.get() != devNamespace.get() &&
                        it.none { s -> s is RemapStepSpec }
                    ) {
                        listOf<StepSpec>(RemapStepSpec()) + it
                    } else it
                })
                t.sourceNamespace.set(inputNamespace)
                t.targetNamespace.set(devNamespace)
                t.mappingFile.set(mappingFile)
                // the source engine resolves members against the libraries
                // plus the binary counterpart of the decompiled artifact
                t.remapClasspath.from(libraries)
                t.remapClasspath.from(devView)
                t.outputArtifact.fileValue(repoSlot)
            }
        }

        if (devSources.isPresent) {
            // transform-provided sources (e.g. forge userdev mcpconfig
            // chain) — remap to the dev namespace, no decompile
            wireSourcesSurface(devSources, environment.devSourcesNamespace)
            return
        }

        val input = decompileInput(project, minecraft, remap, environmentId, devView)

        val spec = object : DecompileSpec {
            override val input: Provider<RegularFile> = input
            override val classpath: FileCollection = libraries
            override val options: Map<String, String>
                // bsm=1 emits vineflower linemap extras (0x4646) for the
                // -linemapped jar; user options override
                get() = mapOf("bsm" to "1") + source.options.get()
        }

        val engine = DecompilerRegistry().select(spec, source.engine.orNull)
        val task = engine.createTask(project, taskName, spec)

        val sourceSteps = remap.pipelineSteps(environmentId, RemapSurface.SOURCES).get()
        task.configure { t ->
            t.group = "unimined"
            t.description = "Decompiles the '$environmentId' Minecraft artifact into a sources jar"
            t.outputJar.set(
                if (sourceSteps.isEmpty()) repoSlot
                else project.layout.buildDirectory.file(
                    "unimined/sources/minecraft-$environmentId-decompiled.jar"
                ).get().asFile
            )
        }

        // -linemapped classifier: binary line tables tracking the
        // (enriched) sources — extras preserved/adjusted by the UML step
        val linemapped = project.tasks.register(
            "gen${if (environmentId == "main") "" else "${environmentId.cap()}"}Linemapped",
            LinemappedJarTask::class.java
        ) { t ->
            t.group = "unimined"
            t.binaryJar.set(input)
            t.sourcesJar.fileValue(repoSlot)
            t.linemappedJar.fileValue(
                java.io.File(repoSlot.absolutePath
                    .replace("-sources.jar", "-linemapped.jar"))
            )
            // the surface task produces repoSlot's sources jar when
            // steps exist — the linemap reads its enriched extras
            if (sourceSteps.isNotEmpty()) {
                t.dependsOn("sourcesSurface${environmentId.cap()}")
            }
        }
        // demand the classifier alongside sources
        task.configure { it.finalizedBy(linemapped) }

        if (sourceSteps.isNotEmpty()) {
            val devNamespace = minecraft.devNamespace(environmentId)
            val mappingFile = minecraft.mappingBundle(
                environmentId, "sources-$environmentId", devNamespace, devNamespace
            )
            project.tasks.register(
                "sourcesSurface${environmentId.cap()}",
                RemapArtifactTask::class.java
            ) { t ->
                t.group = "unimined"
                t.description =
                    "Sources-surface pipeline for the '$environmentId' decompiled sources"
                t.inputArtifact.set(task.flatMap { it.outputJar })
                t.surface.set(RemapSurface.SOURCES)
                t.stepSpecs.set(remap.pipelineSteps(environmentId, RemapSurface.SOURCES))
                t.sourceNamespace.set(devNamespace)
                t.targetNamespace.set(devNamespace)
                t.mappingFile.set(mappingFile)
                // the source engine resolves members against the libraries
                // plus the binary counterpart of the decompiled artifact
                t.remapClasspath.from(libraries)
                t.remapClasspath.from(input)
                t.outputArtifact.fileValue(repoSlot)
            }
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
        val mappingFile = minecraft.mappingBundle(
            environmentId, "decompile-$environmentId", devNamespace, devNamespace
        )
        val fork = project.tasks.register(
            "decompileSurface${environmentId.cap()}",
            RemapArtifactTask::class.java
        ) { t ->
            t.group = "unimined"
            t.description =
                "Decompile-surface fork of the '$environmentId' dev artifact"
            t.inputArtifact.set(devJar)
            t.surface.set(RemapSurface.DECOMPILE)
            t.stepSpecs.set(remap.pipelineSteps(environmentId, RemapSurface.DECOMPILE))
            t.sourceNamespace.set(devNamespace)
            t.targetNamespace.set(devNamespace)
            t.mappingFile.set(mappingFile)
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
