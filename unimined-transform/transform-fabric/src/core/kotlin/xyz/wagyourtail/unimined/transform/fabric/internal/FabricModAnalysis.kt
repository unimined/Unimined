package xyz.wagyourtail.unimined.transform.fabric.internal

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
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
        val id: String? = null,
        val accessWidener: String? = null,
        val classTweakers: JsonElement? = null,
        val mainClass: Map<String, String>? = null,
        val custom: JsonObject? = null
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

    /** The `id` field of the mod's `fabric.mod.json`, if present. */
    fun modId(jar: Path): String? = modJson(jar)?.id

    /** A `custom` section value from the mod's `fabric.mod.json`. */
    fun customValue(jar: Path, key: String): String? =
        modJson(jar)?.custom?.get(key)?.jsonPrimitive?.contentOrNull

    /** Path of the declared `accessWidener` entry in the jar, if any. */
    fun accessWidenerPath(jar: Path): String? = modJson(jar)?.accessWidener

    /**
     * ClassTweaker declarations — path → loader environment
     * (`"client"`/`"server"`/`"*"`). AWs are classTweakers on fmj v1
     * (Loom's `getClassTweakers` parity): the v1 `accessWidener` key
     * contributes a UNIVERSAL entry, and fmj v2's `classTweakers` key is
     * a string or array of `path`/`{config, environment}` entries.
     */
    fun classTweakers(jar: Path): Map<String, String> {
        val mod = modJson(jar) ?: return emptyMap()
        val out = LinkedHashMap<String, String>()
        mod.accessWidener?.let { out[it] = "*" }
        when (val cts = mod.classTweakers) {
            is JsonPrimitive -> if (cts.isString) out[cts.content] = "*"
            is JsonArray -> for (entry in cts) when (entry) {
                is JsonPrimitive -> if (entry.isString) out[entry.content] = "*"
                is JsonObject -> {
                    val config = entry["config"]?.jsonPrimitive?.contentOrNull ?: continue
                    out[config] = entry["environment"]?.jsonPrimitive?.contentOrNull ?: "*"
                }

                else -> {}
            }

            else -> {}
        }
        return out
    }

    /** Raw bytes of a jar entry, or `null` when absent/unreadable. */
    fun readEntry(jar: Path, path: String): ByteArray? = runCatching {
        JarFile(jar.toFile()).use { jarFile ->
            jarFile.getJarEntry(path)?.let { jarFile.getInputStream(it).readBytes() }
        }
    }.getOrNull()

    /**
     * Copy every classTweaker the jar declares into [cacheDir] and return
     * the extracted paths (empty when the jar declares none or is not a
     * fabric mod). Environment filters aren't applied — they gate the
     * loader's *runtime* side, not compile-artifact application.
     */
    fun extractClassTweakers(jar: Path, cacheDir: Path): List<Path> {
        val declarations = classTweakers(jar)
        if (declarations.isEmpty()) return emptyList()
        val out = ArrayList<Path>(declarations.size)
        JarFile(jar.toFile()).use { jarFile ->
            for (entry in declarations.keys) {
                val zipEntry = jarFile.getJarEntry(entry) ?: continue
                val target = cacheDir.resolve(
                    "${jar.fileName.toString().removeSuffix(".jar")}-${entry.substringAfterLast('/')}"
                )
                if (Files.notExists(target)) {
                    Files.createDirectories(target.parent)
                    jarFile.getInputStream(zipEntry).use { input ->
                        Files.copy(input, target)
                    }
                }
                out.add(target)
            }
        }
        return out
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
