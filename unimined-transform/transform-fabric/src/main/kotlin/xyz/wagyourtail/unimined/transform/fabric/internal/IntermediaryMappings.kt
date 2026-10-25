package xyz.wagyourtail.unimined.transform.fabric.internal

import org.gradle.api.Project
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarFile

/**
 * Materializes Fabric's `official -> intermediary` tiny mappings out of
 * the `net.fabricmc:intermediary` Maven jar — the jar packages the tiny
 * v2 document at `mappings/mappings.tiny`, which the mapping pipeline
 * reads as a plain file.
 */
internal object IntermediaryMappings {

    /**
     * Resolve the intermediary artifact for [minecraftVersion] and extract
     * its `mappings/mappings.tiny` into the project's unimined cache.
     */
    fun materialize(project: Project, minecraftVersion: String): Path {
        val cacheDir = project.layout.projectDirectory
            .dir(".gradle/unimined/fabric").asFile.toPath()
        val extracted = cacheDir.resolve("intermediary-$minecraftVersion.tiny")
        if (Files.exists(extracted)) return extracted

        val artifact = project.configurations.detachedConfiguration(
            project.dependencies.create(
                "${FabricModAnalysis.INTERMEDIARY_MODULE}:$minecraftVersion:v2"
            )
        ).apply { isTransitive = false }.singleFile

        JarFile(artifact).use { jar ->
            val entry = jar.getJarEntry("mappings/mappings.tiny")
                ?: throw org.gradle.api.GradleException(
                    "net.fabricmc:intermediary:$minecraftVersion does not contain " +
                        "mappings/mappings.tiny"
                )
            Files.createDirectories(extracted.parent)
            jar.getInputStream(entry).use { input ->
                Files.copy(input, extracted)
            }
        }
        return extracted
    }

}
