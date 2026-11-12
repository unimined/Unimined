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
        val custom: JsonObject? = null,
        @kotlinx.serialization.SerialName("quilt_loom")
        val quiltLoom: JsonObject? = null
    )

    /** The loader's embedded `fabric-installer.json` — its only runtime
     *  dependency declaration (the published pom is intentionally empty). */
    @Serializable
    class FabricInstallerJson(
        val libraries: Map<String, List<Library>> = emptyMap()
    ) {
        @Serializable
        class Library(
            val name: String,
            // e.g. babric's `asm-all:*` disallow — an exclusion
            // marker for artifacts the loader supersedes, never a
            // resolvable coordinate
            val rules: List<Rule> = emptyList()
        ) {
            @Serializable
            class Rule(
                val action: String? = null,
                val os: Os? = null,
                val features: Map<String, Boolean> = emptyMap()
            ) {
                @Serializable
                class Os(
                    val name: String? = null,
                    val version: String? = null,
                    val arch: String? = null
                )
            }

            /** Mojang rule semantics — no rules allow; otherwise the
             *  last rule matching the host platform decides. */
            fun appliesTo(platform: HostPlatform): Boolean {
                if (rules.isEmpty()) return true
                var allowed = false
                for (rule in rules) {
                    if (rule.matches(platform)) allowed = rule.action == "allow"
                }
                return allowed
            }

            private fun Rule.matches(platform: HostPlatform): Boolean {
                val o = os
                if (o != null) {
                    if (o.name != null && o.name != platform.osName) return false
                    if (o.arch != null && !Regex(o.arch).containsMatchIn(platform.arch)) return false
                    if (o.version != null && !Regex(o.version).containsMatchIn(platform.osVersion)) return false
                }
                return features.all { (k, v) -> (platform.features[k] ?: false) == v }
            }
        }
    }

    /** Host platform fields for installer rule evaluation — same shape
     *  as `MinecraftMetadataAssembler`'s `PlatformInfo` (duplicated:
     *  transform core must not depend on unimined-minecraft). */
    class HostPlatform(
        val osName: String,
        val arch: String,
        val osVersion: String,
        val features: Map<String, Boolean> = emptyMap()
    ) {
        companion object {
            fun current(): HostPlatform {
                val os = System.getProperty("os.name").lowercase()
                return HostPlatform(
                    osName = when {
                        os.contains("win") -> "windows"
                        os.contains("mac") || os.contains("darwin") -> "osx"
                        os.contains("linux") -> "linux"
                        else -> os
                    },
                    arch = System.getProperty("os.arch"),
                    osVersion = System.getProperty("os.version")
                )
            }
        }
    }

    /** The `id` field of the mod's `fabric.mod.json`, if present. */
    fun modId(jar: Path): String? = modJson(jar)?.id

    /**
     * A `custom` section value from the mod's `fabric.mod.json`.
     * The entry bytes are scanned for the key before paying for a
     * full JSON parse — jar-processor `buildSpec`s probe every mod
     * jar for keys almost none of them declare, so the raw scan
     * short-circuits the common case. Key-bearing jars fall through
     * to the (cached) parse for the real value.
     */
    fun customValue(jar: Path, key: String): String? {
        val raw = rawEntry(jar, "fabric.mod.json") ?: return null
        if (raw.indexOfSubsequence(key.encodeToByteArray()) < 0) return null
        return modJson(jar)?.custom?.get(key)?.jsonPrimitive?.contentOrNull
    }

    /**
     * Interface injections declared under `custom.*injected_interfaces`
     * keys (the `loom:injected_interfaces`/`quilt_loom.injected_interfaces`
     * convention) — target class internal name → interface internal
     * names, in the jar's production namespace. Consumed by synthesizing
     * classTweaker directives for the dependent's compile artifact.
     */
    fun injectedInterfaces(jar: Path): Map<String, List<String>> {
        val modJson = modJson(jar) ?: return emptyMap()
        val out = LinkedHashMap<String, MutableList<String>>()
        fun collect(obj: JsonObject?) {
            if (obj == null) return
            for ((target, ifaces) in obj) {
                val list = (ifaces as? JsonArray)?.mapNotNull {
                    (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content
                }.orEmpty()
                if (list.isNotEmpty()) {
                    out.getOrPut(target) { mutableListOf() } += list
                }
            }
        }
        // flat keys — fmj `loom:`/`steel:injected_interfaces`, qmj-style
        // `quilt_loom.injected_interfaces` written as a single key
        for ((key, value) in modJson.custom.orEmpty()) {
            if (key.endsWith("injected_interfaces")) {
                collect(value as? JsonObject)
            } else if (value is JsonObject) {
                // nested — qmj's `custom.quilt_loom.injected_interfaces`
                collect(value["injected_interfaces"] as? JsonObject)
            }
        }
        // qmj also accepts a top-level `quilt_loom` object (1.x checked
        // both locations)
        collect(modJson.quiltLoom?.get("injected_interfaces") as? JsonObject)
        return out.mapValues { (_, v) -> v.distinct() }
    }

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
    fun loaderLibraries(jar: Path, side: String? = null): List<String> =
        installerJson(jar)?.let { installer ->
            // 1.x parity: common + development always; the sided
            // sections only on their side (merged gets both)
            val sections = mutableListOf("common", "development")
            if (side == null || side == "client" || side == "merged") {
                sections += "client"
            }
            if (side == null || side == "server" || side == "merged") {
                sections += "server"
            }
            val platform = HostPlatform.current()
            sections.flatMap { section ->
                installer.libraries[section].orEmpty()
                    .filter { it.appliesTo(platform) }
                    .map { it.name }
            }
        } ?: emptyList()

    // daemon-wide parse cache — every public accessor above re-opens
    // the jar and re-parses the json without it, and call sites stack
    // up (modId + customValue + classTweakers + injectedInterfaces +
    // buildSpec's per-mod custom probes run per run). Content-keyed by
    // path+size+mtime so a rebuilt jar can't serve stale metadata.
    private val modJsonCache = java.util.concurrent.ConcurrentHashMap<String, Cached<FabricModJson>>()
    private val installerJsonCache = java.util.concurrent.ConcurrentHashMap<String, Cached<FabricInstallerJson>>()

    private class Cached<T>(val value: T?)

    private fun cacheKey(jar: Path): String {
        val attrs = runCatching {
            Files.readAttributes(jar, java.nio.file.attribute.BasicFileAttributes::class.java)
        }.getOrNull() ?: return jar.toString()
        return "${jar}:${attrs.size()}:${attrs.lastModifiedTime().toMillis()}"
    }

    /** Raw bytes of a jar entry without parsing — the cheap probe
     *  behind [customValue]'s key short-circuit. */
    private fun rawEntry(jar: Path, name: String): ByteArray? = runCatching {
        JarFile(jar.toFile()).use { jarFile ->
            jarFile.getJarEntry(name)
                ?.let { jarFile.getInputStream(it).readBytes() }
        }
    }.getOrNull()

    private fun ByteArray.indexOfSubsequence(needle: ByteArray): Int {
        if (needle.isEmpty() || size < needle.size) return -1
        outer@ for (i in 0..size - needle.size) {
            for (j in needle.indices) {
                if (this[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }

    private fun modJson(jar: Path): FabricModJson? =
        modJsonCache.computeIfAbsent(cacheKey(jar)) {
            Cached(
                // quilt envs' own metadata is `quilt.mod.json` — probe
                // both (1.x read the env's own mod-json path)
                readJarEntry(jar, "fabric.mod.json")
                    ?: readJarEntry(jar, "quilt.mod.json")
            )
        }.value

    private fun installerJson(jar: Path): FabricInstallerJson? =
        installerJsonCache.computeIfAbsent(cacheKey(jar)) {
            Cached(
                readJarEntry(jar, "fabric-installer.json")
                    ?: readJarEntry(jar, "quilt_installer.json")
            )
        }.value

    private inline fun <reified T> readJarEntry(jar: Path, name: String): T? = runCatching {
        JarFile(jar.toFile()).use { jarFile ->
            val entry = jarFile.getJarEntry(name) ?: return null
            jarFile.getInputStream(entry).use { input ->
                json.decodeFromString<T>(input.readBytes().decodeToString())
            }
        }
    }.getOrNull()

}
