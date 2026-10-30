package xyz.wagyourtail.unimined.transform.forge.internal.userdev

import java.nio.file.Path
import java.util.jar.JarFile
import kotlin.io.path.createDirectories
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.outputStream
import xyz.wagyourtail.unimined.transform.forge.internal.UserdevConfig

/**
 * Extraction of `Userdev`-era installer jar contents to the cache —
 * the deterministic half of the userdev pipeline (1.x
 * `FG3MinecraftTransformer` `sources`/`ats`/`inject`/`clientExtra`
 * blocks). The mcpconfig function runner and binpatcher exec consume
 * these outputs.
 */
internal object UserdevExtract {

    /**
     * Extract every entry under [prefix] from [installerJar] to
     * [cacheDir] (1.x `ats` block: `startsWith(prefix)` → directory
     * listing). Returns the extracted files — if the prefix names a
     * single file, a one-element list.
     */
    fun extractPrefix(installerJar: Path, prefix: String, cacheDir: Path): List<Path> {
        cacheDir.createDirectories()
        JarFile(installerJar.toFile()).use { jarFile ->
            jarFile.entries().asSequence()
                .filter { !it.isDirectory && it.name.startsWith(prefix) }
                .forEach { entry ->
                    val target = cacheDir.resolve(entry.name)
                    target.parent.createDirectories()
                    jarFile.getInputStream(entry).use { input ->
                        target.outputStream().use { input.copyTo(it) }
                    }
                }
        }
        val root = cacheDir.resolve(prefix)
        return if (root.isDirectory()) root.listDirectoryEntries().toList()
        else listOf(root).filter { it.toFile().isFile }
    }

    /**
     * Extract fixed entry [names] (not a prefix) from [jar] into
     * [cacheDir]; silently skips absent entries (1.x-era universal
     * jars carry `forge_at.cfg`/`fml_at.cfg` at the root).
     */
    fun extractEntries(jar: Path, names: Collection<String>, cacheDir: Path): List<Path> {
        cacheDir.createDirectories()
        val written = mutableListOf<Path>()
        JarFile(jar.toFile()).use { jarFile ->
            for (name in names) {
                val entry = jarFile.getJarEntry(name) ?: continue
                val target = cacheDir.resolve(name)
                target.parent.createDirectories()
                jarFile.getInputStream(entry).use { input ->
                    target.outputStream().use { input.copyTo(it) }
                }
                written.add(target)
            }
        }
        return written
    }

    /**
     * Extract the `*:sources` classifier jar minus `patches/` — forge
     * source files injected into the decompiled MC sources by the
     * mcpconfig `forgeInject` step.
     */
    fun extractSources(sourcesJar: Path, cacheDir: Path): List<Path> {
        cacheDir.createDirectories()
        val out = mutableListOf<Path>()
        JarFile(sourcesJar.toFile()).use { jarFile ->
            jarFile.entries().asSequence()
                .filter { !it.isDirectory && !it.name.startsWith("patches") }
                .forEach { entry ->
                    val target = cacheDir.resolve(entry.name)
                    target.parent.createDirectories()
                    jarFile.getInputStream(entry).use { input ->
                        target.outputStream().use { input.copyTo(it) }
                    }
                    out.add(target)
                }
        }
        return out
    }

    /**
     * Entries under the userdev `inject` prefix, keyed by target path
     * (prefix stripped) — 1.x `addTransform` copies them into the
     * patched jar verbatim.
     */
    fun injectEntries(installerJar: Path, prefix: String): Map<String, ByteArray> {
        val out = linkedMapOf<String, ByteArray>()
        JarFile(installerJar.toFile()).use { jarFile ->
            jarFile.entries().asSequence()
                .filter { !it.isDirectory && it.name.startsWith(prefix) }
                .forEach { entry ->
                    out[entry.name.substring(prefix.length)] =
                        jarFile.getInputStream(entry).use { it.readBytes() }
                }
        }
        return out
    }

    /**
     * Classpath-side library coordinates from the userdev `libraries`
     * array — 1.x `apply()`: `legacydev` entries skipped, `gson:2.8.0`
     * pinned up to `2.8.9`.
     */
    fun libraryCoords(config: UserdevConfig): List<String> =
        config.libraries.filter { !it.contains("legacydev") }.map { coord ->
            val parts = coord.split(":")
            if (parts.size >= 3 && parts[1] == "gson" && parts[2] == "2.8.0")
                "${parts[0]}:${parts[1]}:2.8.9"
            else coord
        }

    /**
     * Write `client-extra-<ver>.jar`: every non-class, non-`META-INF`
     * file from the vanilla client jar (assets/data 1.x carries on the
     * classpath beside the patched jar).
     */
    fun writeClientExtra(clientJar: Path, outJar: Path) {
        if (outJar.toFile().isFile) return
        outJar.parent.createDirectories()
        JarFile(clientJar.toFile()).use { inJar ->
            java.util.jar.JarOutputStream(outJar.outputStream()).use { out ->
                inJar.entries().asSequence()
                    .filter { !it.isDirectory }
                    .filter {
                        !it.name.startsWith("META-INF/") &&
                                !it.name.endsWith(".class")
                    }
                    .forEach { entry ->
                        out.putNextEntry(java.util.jar.JarEntry(entry.name))
                        inJar.getInputStream(entry).use { it.copyTo(out) }
                        out.closeEntry()
                    }
            }
        }
    }
}
