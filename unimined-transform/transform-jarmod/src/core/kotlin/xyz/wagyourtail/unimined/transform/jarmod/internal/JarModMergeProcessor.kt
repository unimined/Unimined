package xyz.wagyourtail.unimined.transform.jarmod.internal

import xyz.wagyourtail.unimined.api.util.writeAtomically
import xyz.wagyourtail.commonskt.compress.CONSTANT_TIME_FOR_ZIP_ENTRIES
import xyz.wagyourtail.commonskt.compress.putEntryWithDirs
import xyz.wagyourtail.commonskt.utils.toHex
import xyz.wagyourtail.unimined.transform.processor.JarProcessor
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Merges the env's `jarMod` configuration into the dev Minecraft jar —
 * the 1.x jarmod FileSystem merge as a [JarProcessor] so it rides the
 * dev-Minecraft transform (content-fingerprinted, cached). Forge
 * wiring feeds the same `jarmod:merge` env-artifact binding to
 * shadow loader classes into the product (FG1 forge zips, FG2
 * universal jar).
 *
 * Merge-source entries win on collisions. Signature material is
 * always dropped from merge sources; `deleteMetaInf` additionally
 * strips the base jar's META-INF.
 */
class JarModMergeProcessor(
    private val jarMod: List<Path>? = null,
    private val deleteMetaInf: (() -> Boolean)? = null,
    private val excludeEntries: Set<String> = emptySet()
) : JarProcessor {

    override val name = "jarmod-merge"
    override val preRemap = true
    // merged-in content can carry inner classes of patch-target classes
    // the composed mappings have no row for — diff the merge for the
    // inner-class augmentation (1.x ran the same hook over its merged jar)
    override val contributesPatchAddedClasses = true

    private data class Spec(
        val jars: List<Entry>,
        val deleteMetaInf: Boolean,
        val excludes: List<String> = emptyList()
    ) : JarProcessor.JarProcessorSpec {
        data class Entry(val path: String, val sha1: String) : java.io.Serializable

    }

    override fun buildSpec(context: JarProcessor.SpecContext): JarProcessor.JarProcessorSpec? {
        val jars = (jarMod.orEmpty() + context.envArtifacts("jarmod:merge"))
            .filter { java.nio.file.Files.isRegularFile(it) }
        if (jars.isEmpty()) return null
        return Spec(
            jars.map { Spec.Entry(it.toAbsolutePath().toString(), sha1(it.toFile())) },
            deleteMetaInf?.invoke() ?: false,
            excludeEntries.toList()
        )
    }

    override fun serializeSpec(spec: JarProcessor.JarProcessorSpec): String {
        spec as Spec
        return buildString {
            append(spec.deleteMetaInf)
            append('\n').append(spec.excludes.size)
            for (p in spec.excludes) append('\n').append(p)
            for (e in spec.jars) append('\n').append(e.path).append(':').append(e.sha1)
        }
    }

    override fun deserializeSpec(payload: String): JarProcessor.JarProcessorSpec {
        val lines = payload.split('\n')
        val excludeCount = lines.getOrNull(1)?.toIntOrNull() ?: 0
        return Spec(
            lines.drop(2 + excludeCount).filter { it.isNotEmpty() }.map {
                val i = it.lastIndexOf(':')
                Spec.Entry(it.substring(0, i), it.substring(i + 1))
            },
            lines.first().toBoolean(),
            lines.drop(2).take(excludeCount)
        )
    }

    override fun processJar(
        jar: Path,
        spec: JarProcessor.JarProcessorSpec,
        context: JarProcessor.ProcessorContext
    ) {
        spec as Spec
        jar.writeAtomically { tmp ->
            ZipOutputStream(Files.newOutputStream(tmp)).use { out ->
                val emittedDirs = mutableSetOf<String>()
                val seen = mutableSetOf<String>()
                val excludes = spec.excludes.map(::Regex)
                fun copyFrom(path: Path, stripAllMetaInf: Boolean, isModJar: Boolean) {
                    ZipFile(path.toFile()).use { zip ->
                        zip.entries().asSequence()
                            .filter { !it.isDirectory }
                            .forEach { entry ->
                                var name = entry.name.replace('\\', '/')
                                // merge-source content ships runtime patch
                                // material (e.g. forge's binpatches.pack) —
                                // FML would try to apply it at launch
                                if (isModJar && excludes.any { it.matches(name) }) {
                                    return@forEach
                                }
                                // FML-era convention: `_`-prefixed class
                                // entries mark replacements for the bare-named
                                // class — strip so the patch overwrites it
                                if (isModJar && name.startsWith('_') && name.endsWith(".class")) {
                                    name = name.substring(1)
                                }
                                if (skip(name, stripAllMetaInf)) return@forEach
                                if (!seen.add(name)) return@forEach
                                out.putEntryWithDirs(
                                    name, emittedDirs, CONSTANT_TIME_FOR_ZIP_ENTRIES
                                ) { o -> zip.getInputStream(entry).use { it.copyTo(o) } }
                            }
                    }
                }
                // jarmod jars first — they win on collision
                for (e in spec.jars) copyFrom(Path.of(e.path), stripAllMetaInf = true, isModJar = true)
                copyFrom(jar, stripAllMetaInf = spec.deleteMetaInf, isModJar = false)
            }
        }
        context.log.info("[JarMod] merged ${spec.jars.size} jar(s) into ${jar.fileName}")
    }

    private fun skip(name: String, stripAllMetaInf: Boolean): Boolean {
        val upper = name.uppercase()
        if (!upper.startsWith("META-INF/")) return false
        if (stripAllMetaInf) return true
        return upper == "META-INF/MANIFEST.MF" ||
            upper.endsWith(".SF") || upper.endsWith(".RSA") || upper.endsWith(".DSA")
    }

    private fun sha1(file: File): String {
        val digest = MessageDigest.getInstance("SHA-1")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var read = input.read(buffer)
            while (read != -1) {
                digest.update(buffer, 0, read)
                read = input.read(buffer)
            }
        }
        return digest.digest().toHex()
    }

}
