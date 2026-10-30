package xyz.wagyourtail.unimined.transform.jarmod.internal

import org.gradle.api.file.FileCollection
import org.gradle.api.provider.Provider
import xyz.wagyourtail.commonskt.compress.CONSTANT_TIME_FOR_ZIP_ENTRIES
import xyz.wagyourtail.commonskt.compress.putEntryWithDirs
import xyz.wagyourtail.unimined.transform.processor.JarProcessor
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Merges the env's `jarMod` configuration into the dev Minecraft jar —
 * the 1.x jarmod FileSystem merge as a [JarProcessor] so it rides the
 * dev-Minecraft transform (content-fingerprinted, cached).
 *
 * Jarmod entries win on collisions. Signature material is always
 * dropped from jarmod sources; `deleteMetaInf` additionally strips the
 * base jar's META-INF.
 */
internal class JarModMergeProcessor(
    private val jarMod: FileCollection? = null,
    private val deleteMetaInf: Provider<Boolean>? = null
) : JarProcessor {

    override val name = "jarmod-merge"
    override val preRemap = true

    private data class Spec(
        val jars: List<Entry>,
        val deleteMetaInf: Boolean
    ) : JarProcessor.JarProcessorSpec {
        data class Entry(val path: String, val sha1: String) : java.io.Serializable
    }

    override fun buildSpec(context: JarProcessor.SpecContext): JarProcessor.JarProcessorSpec? {
        val jars = (jarMod ?: return null).files.filter { it.isFile }
        if (jars.isEmpty()) return null
        return Spec(
            jars.map { Spec.Entry(it.absolutePath, sha1(it)) },
            deleteMetaInf?.orElse(false)?.get() ?: false
        )
    }

    override fun serializeSpec(spec: JarProcessor.JarProcessorSpec): String {
        spec as Spec
        return buildString {
            append(spec.deleteMetaInf)
            for (e in spec.jars) append('\n').append(e.path).append(':').append(e.sha1)
        }
    }

    override fun deserializeSpec(payload: String): JarProcessor.JarProcessorSpec {
        val lines = payload.split('\n')
        return Spec(
            lines.drop(1).filter { it.isNotEmpty() }.map {
                val i = it.lastIndexOf(':')
                Spec.Entry(it.substring(0, i), it.substring(i + 1))
            },
            lines.first().toBoolean()
        )
    }

    override fun processJar(
        jar: Path,
        spec: JarProcessor.JarProcessorSpec,
        context: JarProcessor.ProcessorContext
    ) {
        spec as Spec
        val tmp = Files.createTempFile(jar.parent, "jarmod-merge", ".jar")
        try {
            ZipOutputStream(Files.newOutputStream(tmp)).use { out ->
                val emittedDirs = mutableSetOf<String>()
                val seen = mutableSetOf<String>()
                fun copyFrom(path: Path, stripAllMetaInf: Boolean, isModJar: Boolean) {
                    ZipFile(path.toFile()).use { zip ->
                        zip.entries().asSequence()
                            .filter { !it.isDirectory }
                            .forEach { entry ->
                                var name = entry.name.replace('\\', '/')
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
            Files.move(tmp, jar, StandardCopyOption.REPLACE_EXISTING)
            context.log.info("[JarMod] merged ${spec.jars.size} jar(s) into ${jar.fileName}")
        } catch (t: Throwable) {
            Files.deleteIfExists(tmp)
            throw t
        }
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
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

}
