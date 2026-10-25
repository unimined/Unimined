package xyz.wagyourtail.unimined.transform.fabric.internal

import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarFile

/**
 * Materializes Fabric's `official -> intermediary` tiny mappings out of
 * the resolved `net.fabricmc:intermediary` Maven jar — the jar packages
 * the tiny v2 document at `mappings/mappings.tiny`, which the mapping
 * pipeline reads as a plain file.
 */
internal object IntermediaryMappings {

    const val INTERMEDIARY_MODULE = "net.fabricmc:intermediary"

    /**
     * Extract `mappings/mappings.tiny` from [intermediaryJar] into
     * [cacheDir], keyed by [minecraftVersion]. Idempotent — an existing
     * extracted file is reused.
     */
    fun materialize(intermediaryJar: Path, minecraftVersion: String, cacheDir: Path): Path {
        val extracted = cacheDir.resolve("intermediary-$minecraftVersion.tiny")
        if (Files.exists(extracted)) return extracted

        JarFile(intermediaryJar.toFile()).use { jar ->
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
