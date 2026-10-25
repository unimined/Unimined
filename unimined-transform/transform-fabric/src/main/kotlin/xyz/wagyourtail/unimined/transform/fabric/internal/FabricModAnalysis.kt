package xyz.wagyourtail.unimined.transform.fabric.internal

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarFile

/**
 * Reads `fabric.mod.json` metadata out of loader and mod jars: access
 * widener declarations and launch main classes.
 */
internal object FabricModAnalysis {

    const val LOADER_MODULE = "net.fabricmc:fabric-loader"
    const val INTERMEDIARY_MODULE = "net.fabricmc:intermediary"
    const val FABRIC_MAVEN = "https://maven.fabricmc.net/"

    private val json = Json { ignoreUnknownKeys = true }

    /** Path of the declared `accessWidener` entry in the jar, if any. */
    fun accessWidenerPath(jar: Path): String? = modJson(jar)
        ?.get("accessWidener")?.jsonPrimitive?.content

    /**
     * Copy the jar's declared access widener into [cacheDir] and return
     * its path; `null` when the jar declares none or is not a fabric mod.
     */
    fun extractAccessWidener(jar: Path, cacheDir: Path): Path? {
        val entry = accessWidenerPath(jar) ?: return null
        JarFile(jar.toFile()).use { jarFile ->
            val zipEntry = jarFile.getJarEntry(entry) ?: return null
            val target = cacheDir.resolve(
                "${jar.fileName.toString().removeSuffix(".jar")}-${entry.substringAfterLast('/')}"
            )
            if (Files.notExists(target)) {
                Files.createDirectories(target.parent)
                jarFile.getInputStream(zipEntry).use { input ->
                    Files.copy(input, target)
                }
            }
            return target
        }
    }

    /** The loader's `mainClass` for [side] (`client`/`server`), if declared. */
    fun loaderMainClass(jar: Path, side: String): String? = modJson(jar)
        ?.get("mainClass")?.jsonObject
        ?.get(side)?.jsonPrimitive?.content

    /**
     * Maven coordinates of the loader's own runtime libraries, read from
     * the embedded `fabric-installer.json` (`libraries.common` +
     * `libraries.server` — client-only libraries don't exist in practice
     * and including server libs on a client run is harmless). The
     * loader's pom intentionally declares no dependencies.
     */
    fun loaderLibraries(jar: Path): List<String> = runCatching {
        JarFile(jar.toFile()).use { jarFile ->
            val entry = jarFile.getJarEntry("fabric-installer.json") ?: return emptyList()
            val doc = jarFile.getInputStream(entry).use { input ->
                json.parseToJsonElement(input.readBytes().decodeToString()).jsonObject
            }
            val libraries = doc["libraries"]?.jsonObject ?: return emptyList()
            listOf("common", "server", "client").flatMap { section ->
                libraries[section]?.jsonArray
                    ?.mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.content }
                    ?: emptyList()
            }
        }
    }.getOrElse { emptyList() }

    private fun modJson(jar: Path): JsonObject? = runCatching {
        JarFile(jar.toFile()).use { jarFile ->
            val entry = jarFile.getJarEntry("fabric.mod.json") ?: return null
            jarFile.getInputStream(entry).use { input ->
                json.parseToJsonElement(input.readBytes().decodeToString()).jsonObject
            }
        }
    }.getOrNull()

}
