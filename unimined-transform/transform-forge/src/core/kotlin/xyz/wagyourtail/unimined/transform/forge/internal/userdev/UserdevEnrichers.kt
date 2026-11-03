package xyz.wagyourtail.unimined.transform.forge.internal.userdev

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Path
import java.util.jar.JarFile
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import xyz.wagyourtail.unimined.transform.forge.internal.UserdevConfig

/**
 * FG6-era source/binary enrichers carried in userdev `data`:
 * `access` (AT file, JST format), `interfaces` (injected-interface
 * file, `target iface...` lines) and `enum` (`target field` lines).
 * 1.x never consumed these — applying them to the dev jar via
 * `apply-class-tweaker` and to generated sources via the `jst` step
 * matches ForgeGradle 6's pipeline.
 */
internal object UserdevEnrichers {

    /**
     * Side-aware `data` entry → jar-internal path (1.x `getData`):
     * strings resolve directly, `{client,server}` objects pick the
     * environment side (`joined` reads `client`).
     */
    fun dataPath(config: UserdevConfig, key: String, side: String): String? {
        val el = config.data?.get(key) ?: return null
        (el as? JsonPrimitive)?.contentOrNull?.let { return it }
        return (el as? JsonObject)?.let {
            it[side]?.jsonPrimitive?.contentOrNull
                ?: it["client"]?.jsonPrimitive?.contentOrNull
        }
    }

    /** Extract the jar entries at [paths] into [outDir]; returns the
     *  written files (skipped keys produce no file). */
    fun extract(jar: Path, paths: Map<String, String>, outDir: Path): Map<String, Path> {
        outDir.createDirectories()
        val out = linkedMapOf<String, Path>()
        JarFile(jar.toFile()).use { j ->
            for ((key, path) in paths) {
                val entry = j.getJarEntry(path) ?: continue
                val target = outDir.resolve(key + "-" + path.substringAfterLast('/'))
                j.getInputStream(entry).use { input ->
                    target.writeBytes(input.readBytes())
                }
                out[key] = target
            }
        }
        return out
    }

    /**
     * The extracted enrichers as ClassTweaker inputs for
     * `apply-class-tweaker`: `access` passes through (AT format is
     * auto-detected), `interfaces`/`enum` are converted to
     * `inject-interface`/`extend-enum` directives in a generated file
     * whose header namespace is [namespace] (the userdev/obf namespace
     * the file's names live in — the step rebases from there).
     */
    fun classTweakerFiles(
        extracted: Map<String, Path>,
        namespace: String,
        outDir: Path
    ): List<Path> {
        val files = mutableListOf<Path>()
        extracted["access"]?.let { files.add(it) }
        extracted["interfaces"]?.let { file ->
            files.add(
                writeCt(
                    outDir.resolve("userdev_injected_interfaces.cfg"),
                    "classTweaker v1 $namespace",
                    interfaceDirectives(file.readText())
                )
            )
        }
        extracted["enum"]?.let { file ->
            files.add(
                writeCt(
                    outDir.resolve("userdev_enum_extensions.cfg"),
                    "classTweaker v2 $namespace",
                    enumDirectives(file.readText())
                )
            )
        }
        return files
    }

    /** `target iface1 iface2...` → `inject-interface target iface` rows. */
    private fun interfaceDirectives(text: String): List<String> =
        text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .flatMap { line ->
                val fields = line.split(Regex("\\s+"))
                if (fields.size < 2) emptyList()
                else fields.drop(1).map { "inject-interface ${fields[0]} $it" }
            }.toList()

    /** `target param` → `extend-enum target param` rows (v2). */
    private fun enumDirectives(text: String): List<String> =
        text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .mapNotNull { line ->
                val fields = line.split(Regex("\\s+"))
                if (fields.size < 2) null
                else "extend-enum ${fields[0]} ${fields[1]}"
            }.toList()

    private fun writeCt(path: Path, header: String, directives: List<String>): Path {
        if (directives.isEmpty()) return path.also { it.writeText("$header\n") }
        path.writeText(buildString {
            append(header).append('\n')
            directives.forEach { append(it).append('\n') }
        })
        return path
    }
}
