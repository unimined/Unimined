package xyz.wagyourtail.unimined.source.internal

import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.LineNumberNode
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Applies the vineflower linemap extras embedded in a sources jar to
 * the matching binary jar — 1.x `lineMapJar` port. Produces the
 * `-linemapped` classifier whose `LineNumberTable`s track the
 * decompiled/enriched source lines for debugging.
 */
abstract class LinemappedJarTask : DefaultTask() {

    @get:InputFile
    abstract val binaryJar: RegularFileProperty

    @get:InputFile
    abstract val sourcesJar: RegularFileProperty

    @get:OutputFile
    abstract val linemappedJar: RegularFileProperty

    /** Vineflower `0x4646` extra → decompiled line → bytecode line. */
    private fun readLinemap(extra: ByteArray?): Map<Int, Int>? {
        if (extra == null || extra.size < (Short.SIZE_BYTES * 2)) return null
        val buffer = ByteBuffer.wrap(extra).order(ByteOrder.LITTLE_ENDIAN)
        while (buffer.short != 0x4646.toShort()) {
            val length = buffer.short
            buffer.position(buffer.position() + length)
            if (!buffer.hasRemaining()) return null
        }
        val length = buffer.short
        if (length < 1) return null
        val version = buffer.get()
        if (version != 1.toByte()) {
            project.logger.warn("[LinemappedJar] unknown linemap version $version")
            return null
        }
        val mapSize = (length - 1) / (Short.SIZE_BYTES * 2)
        val map = mutableMapOf<Int, Int>()
        for (i in 0 until mapSize) {
            if (!buffer.hasRemaining()) {
                project.logger.warn("[LinemappedJar] linemap ended early")
                return null
            }
            map[buffer.short.toInt()] = buffer.short.toInt()
        }
        return map.takeIf { it.isNotEmpty() }
    }

    @TaskAction
    fun lineMapJar() {
        val extras = mutableMapOf<String, ByteArray>()
        ZipFile(sourcesJar.get().asFile).use { zf ->
            zf.entries().asSequence().forEach { e ->
                extras[e.name] = e.extra ?: ByteArray(0)
            }
        }

        val out = linemappedJar.get().asFile
        out.parentFile.mkdirs()
        ZipOutputStream(out.outputStream().buffered()).use { zo ->
            ZipFile(binaryJar.get().asFile).use { zf ->
                zf.entries().asSequence().forEach { e ->
                    zo.putNextEntry(ZipEntry(e.name))
                    val bytes = zf.getInputStream(e).readBytes()
                    if (!e.name.endsWith(".class")) {
                        zo.write(bytes); zo.closeEntry(); return@forEach
                    }
                    val node = ClassNode()
                    ClassReader(bytes).accept(node, 0)
                    val sourceFile = node.sourceFile
                        ?: (node.name.substringAfterLast("/")
                            .substringBefore("$") + ".java")
                    val linemap = readLinemap(extras[sourceFile])
                    if (linemap != null) {
                        for (m in node.methods) for (i in m.instructions) {
                            if (i is LineNumberNode) {
                                linemap[i.line]?.let { i.line = it }
                            }
                        }
                    }
                    zo.write(ClassWriter(0).also { node.accept(it) }.toByteArray())
                    zo.closeEntry()
                }
            }
        }
    }

}
