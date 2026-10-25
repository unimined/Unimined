package xyz.wagyourtail.unimined.transform.fabric.internal

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
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

    /** The loader/mod `fabric.mod.json` fields unimined consumes. */
    @Serializable
    class FabricModJson(
        val accessWidener: String? = null,
        val mainClass: Map<String, String>? = null
    )

    /** The loader's embedded `fabric-installer.json` — its only runtime
     *  dependency declaration (the published pom is intentionally empty). */
    @Serializable
    class FabricInstallerJson(
        val libraries: Map<String, List<Library>> = emptyMap()
    ) {
        @Serializable
        class Library(val name: String)
    }

    /** Path of the declared `accessWidener` entry in the jar, if any. */
    fun accessWidenerPath(jar: Path): String? = modJson(jar)?.accessWidener

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
        ?.mainClass?.get(side)

    /**
     * Maven coordinates of the loader's own runtime libraries, read from
     * the embedded `fabric-installer.json` (`libraries.common` +
     * `libraries.server` — client-only libraries don't exist in practice
     * and including server libs on a client run is harmless). The
     * loader's pom intentionally declares no dependencies.
     */
    fun loaderLibraries(jar: Path): List<String> = installerJson(jar)
        ?.let { installer ->
            listOf("common", "server", "client").flatMap { section ->
                installer.libraries[section].orEmpty().map { it.name }
            }
        }
        ?: emptyList()

    private fun modJson(jar: Path): FabricModJson? = readJarEntry(jar, "fabric.mod.json")

    private fun installerJson(jar: Path): FabricInstallerJson? =
        readJarEntry(jar, "fabric-installer.json")

    private inline fun <reified T> readJarEntry(jar: Path, name: String): T? = runCatching {
        JarFile(jar.toFile()).use { jarFile ->
            val entry = jarFile.getJarEntry(name) ?: return null
            jarFile.getInputStream(entry).use { input ->
                json.decodeFromString<T>(input.readBytes().decodeToString())
            }
        }
    }.getOrNull()

}
