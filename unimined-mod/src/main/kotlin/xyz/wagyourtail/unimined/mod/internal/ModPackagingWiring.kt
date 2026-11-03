package xyz.wagyourtail.unimined.mod.internal

import org.gradle.api.Project
import org.gradle.api.tasks.SourceSet
import xyz.wagyourtail.unimined.minecraft.MinecraftEnvironment
import xyz.wagyourtail.unimined.minecraft.MinecraftExtension
import xyz.wagyourtail.unimined.mod.ModExtension
import xyz.wagyourtail.unimined.mod.task.GenerateModMetadataTask

/**
 * Wires `generate<Env>ModMetadata` per bound environment: captures the
 * `mod {}` model into task inputs, registers the task's output directory
 * as a resources srcDir on the environment's source set, and hands the
 * source set's own resource dirs to the task for collision checking.
 */
internal object ModPackagingWiring {

    fun wire(project: Project, environment: MinecraftEnvironment, sourceSet: SourceSet) {
        val environmentId = environment.environmentId
        val taskName = if (environmentId == "main") {
            "generateModMetadata"
        } else {
            "generate${environmentId.cap()}ModMetadata"
        }

        val outDir = project.layout.buildDirectory
            .dir("generated/unimined/mod/$environmentId")

        val task = project.tasks.register(taskName, GenerateModMetadataTask::class.java) { t ->
            t.group = "unimined"
            t.description =
                "Generates mod metadata and mixin configs for the '$environmentId' environment"
            t.environmentId.set(environmentId)
            t.outputDir.set(outDir)

            val mod = project.extensions.getByType(ModExtension::class.java)
            val minecraft = project.extensions.getByType(MinecraftExtension::class.java)

            t.metadata.set(project.provider { mod.snapshot() })
            t.mixinConfigs.set(
                project.provider { mod.mixins.map { it.snapshot() } }
            )
            t.loaderName.set(environment.loaderSpec.map { it.name })
            t.minecraftVersion.set(environment.version)
            t.side.set(environment.side.map { it.name })
            t.devNamespace.set(minecraft.devNamespace(environmentId))
            t.prodNamespaces.set(minecraft.modSourceNamespaces(environmentId))
            // plain paths, not the live FileCollection — the generated
            // srcDir below would otherwise feed the task's own output back
            // as an input and self-depend
            t.resourceDirs.from(project.provider {
                sourceSet.resources.srcDirs - outDir.get().asFile
            })
        }

        sourceSet.resources.srcDir(task.flatMap { it.outputDir })
    }

    private fun String.cap(): String = replaceFirstChar(Char::uppercase)

}
