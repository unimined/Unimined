package xyz.wagyourtail.unimined.transform.forge.internal

import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarFile

/**
 * Reads Forge/FML access-transformer declarations out of mod jars —
 * the apply-side discovery set only (1.x `ModRemapProvider` parity):
 * `META-INF/accesstransformer.cfg` plus the `FMLAT` jar-manifest
 * attribute (old-FML custom AT paths, space-separated). The broader
 * `*_at.cfg` matching 1.x used was for *remapping* AT files inside mod
 * jars, which FML does not treat as application declarations.
 *
 * ATs carry no environment or transitive gating — every discovered
 * file applies to the shared Minecraft artifact.
 */
internal object ForgeModAnalysis {

    const val FORGE_AT = "META-INF/accesstransformer.cfg"

    /**
     * AccessTransformer declarations — path → `"*"` (uniform shape with
     * fabric's `classTweakers`; ATs have no environment filter).
     */
    fun classTweakers(jar: Path): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        JarFile(jar.toFile()).use { jarFile ->
            if (jarFile.getJarEntry(FORGE_AT) != null) out[FORGE_AT] = "*"
            jarFile.manifest?.mainAttributes?.getValue("FMLAT")
                ?.split(" ")
                ?.filter { it.isNotBlank() }
                ?.forEach { out.putIfAbsent(it, "*") }
        }
        return out
    }

    /**
     * Copy every accessTransformer the jar declares into [cacheDir] and
     * return the extracted paths (empty when the jar declares none).
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
}
