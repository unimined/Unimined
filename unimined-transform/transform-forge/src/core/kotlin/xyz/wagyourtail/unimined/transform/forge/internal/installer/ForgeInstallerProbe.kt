package xyz.wagyourtail.unimined.transform.forge.internal.installer

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.nio.file.Path
import java.util.jar.JarFile
import xyz.wagyourtail.unimined.transform.forge.internal.ForgeProfile
import xyz.wagyourtail.unimined.transform.forge.internal.UserdevConfig

/**
 * Classifies a resolved forge-family loader artifact into its packaging
 * era ([ForgeProfile]) by content shape — the single place era
 * detection lives. Product identity (forge/neoforge/cleanroom) is
 * unrelated; all three flow through the same probe.
 *
 * Resolution order the wiring uses:
 * 1. `*:userdev` classifier at the descriptor's base GAV — if it
 *    exists, [inspect] it directly (FG3+ `config.json`, or the FG2
 *    `dev.json` dev-package which becomes
 *    [ForgeProfile.Binpatches.devPackage]).
 * 2. else `*:universal`/`@zip` base artifact — [inspect] →
 *    `Binpatches`/`ZipPatches`.
 */
internal object ForgeInstallerProbe {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Classify [jar] by its contents. Detection is order-sensitive:
     * userdev jars may also carry `binpatches.pack.lzma`, so
     * `config.json`/`dev.json` are checked first.
     */
    fun inspect(jar: Path): ForgeProfile {
        JarFile(jar.toFile()).use { jarFile ->
            val names = jarFile.entries().asSequence().map { it.name }.toSet()

            // FG3-6 userdev/userdev3: mcpconfig-style config.json
            if ("config.json" in names) {
                return ForgeProfile.Userdev(
                    UserdevConfig(readJson(jarFile, "config.json"))
                )
            }

            // FG2 dev package: dev.json + devbinpatches (preferred over
            // the universal path when the userdev classifier exists)
            if ("dev.json" in names && "devbinpatches.pack.lzma" in names) {
                return ForgeProfile.Binpatches(
                    ForgeProfile.Binpatches.DevPackage(jar, readJson(jarFile, "dev.json"))
                )
            }

            // FG2 universal: release binpatches; `version.json` (when
            // embedded — pre-`userdev3` era) carries the dev metadata
            if ("binpatches.pack.lzma" in names) {
                return ForgeProfile.Binpatches(
                    null,
                    if ("version.json" in names)
                        readJson(jarFile, "version.json")
                    else null
                )
            }

            // FG1: patch class files baked into the zip/jar
            if (names.any { it.startsWith("net/minecraft/") && it.endsWith(".class") }) {
                return ForgeProfile.ZipPatches
            }

            error(
                "Unrecognized forge installer shape in $jar " +
                        "(no config.json, dev.json, binpatches.pack.lzma, or net/minecraft patches)"
            )
        }
    }

    /** `true` when [jar] contains [entry] — used by the loader
     *  resolution's `userdev3` hop (a universal carrying
     *  `binpatches.pack.lzma` pairs with the `userdev3` classifier). */
    fun hasEntry(jar: Path, entry: String): Boolean =
        runCatching {
            JarFile(jar.toFile()).use { it.getJarEntry(entry) != null }
        }.getOrDefault(false)

    /** `true` when [jar] is an FG2-era dev package suitable for
     *  [ForgeProfile.Binpatches.devPackage]. */
    fun isDevPackage(jar: Path): Boolean =
        runCatching { inspect(jar) }.getOrNull()
            .let { it is ForgeProfile.Binpatches && it.devPackage != null }

    private fun readJson(jarFile: JarFile, name: String): JsonObject {
        val entry = jarFile.getJarEntry(name)
            ?: error("$name not found in ${jarFile.name}")
        return jarFile.getInputStream(entry).use {
            json.parseToJsonElement(it.readBytes().decodeToString()) as JsonObject
        }
    }
}
