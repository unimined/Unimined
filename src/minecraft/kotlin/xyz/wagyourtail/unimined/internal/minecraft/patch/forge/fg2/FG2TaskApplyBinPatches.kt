/*
 * A Gradle plugin for the creation of Minecraft mods and MinecraftForge plugins.
 * Copyright (C) 2013 Minecraft Forge
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301
 * USA
 */
package xyz.wagyourtail.unimined.internal.minecraft.patch.forge.fg2

import com.google.common.base.Joiner
import com.google.common.collect.Maps
import com.google.common.io.ByteStreams
import com.nothome.delta.GDiffPatcher
import lzma.sdk.lzma.Decoder
import lzma.streams.LzmaInputStream
import org.apache.commons.compress.harmony.unpack200.Pack200UnpackerAdapter
import org.gradle.api.Project
import org.jetbrains.annotations.ApiStatus
import java.io.*
import java.util.*
import java.util.jar.JarEntry
import java.util.jar.JarInputStream
import java.util.jar.JarOutputStream
import java.util.regex.Pattern
import java.util.zip.*

@ApiStatus.Internal
class FG2TaskApplyBinPatches(private val project: Project) {
    private val patches: HashMap<String, ClassPatch> = Maps.newHashMap()
    private val patcher = GDiffPatcher()

    @Throws(IOException::class)
    fun doTask(input: File, patches: File, output: File, side: String) {
        setup(patches, side)
        output.delete()
        val entries = HashSet<String>()
        ZipFile(input).use { `in` ->
            ZipOutputStream(BufferedOutputStream(FileOutputStream(output))).use { out ->
                // DO PATCHES
                log("Patching Class:")
                for (e in Collections.list(`in`.entries())) {
                    if (e.isDirectory) {
                        out.putNextEntry(e)
                    } else if (e.name.contains("META-INF")) {
                        val n = ZipEntry(e.name)
                        n.time = e.time
                        out.putNextEntry(n)
                    } else {
                        val n = ZipEntry(e.name)
                        n.time = e.time
                        out.putNextEntry(n)
                        var data = ByteStreams.toByteArray(
                            `in`.getInputStream(
                                e
                            )
                        )
                        val patch = this.patches[e.name.replace('\\', '/')]
                        if (patch != null) {
                            log(
                                "\t%s (%s) (input size %d)",
                                patch.targetClassName,
                                patch.sourceClassName,
                                data.size
                            )
                            val inputChecksum = adlerHash(data)
                            if (patch.inputChecksum != inputChecksum) {
                                throw RuntimeException(
                                    String.format(
                                        "There is a binary discrepancy between the expected input class %s (%s) and the actual class. Checksum on disk is %x, in patch %x. Things are probably about to go very wrong. Did you put something into the jar file?",
                                        patch.targetClassName,
                                        patch.sourceClassName,
                                        inputChecksum,
                                        patch.inputChecksum
                                    )
                                )
                            }
                            synchronized(patcher) { data = patcher.patch(data, patch.patch) }
                        }
                        out.write(data)
                    }

                    // add the names to the hashset
                    entries.add(e.name)
                }
            }
        }
    }

    fun setup(patches: File, side: String) {
        val matcher = Pattern.compile(String.format("binpatch/%s/.*.binpatch", side))
        val jis: JarInputStream = try {
            LzmaInputStream(FileInputStream(patches), Decoder()).use { uncompressed ->
                val jarBytes = ByteArrayOutputStream()
                JarOutputStream(jarBytes).use {
                    Pack200UnpackerAdapter().unpack(uncompressed, it)
                }
                JarInputStream(ByteArrayInputStream(jarBytes.toByteArray()))
            }
        } catch (e: Exception) {
            throw RuntimeException(e)
        }
        log("Reading Patches:")
        do {
            try {
                val entry = jis.nextJarEntry ?: break
                if (matcher.matcher(entry.name).matches()) {
                    val cp = readPatch(entry, jis)
                    this.patches[cp.sourceClassName.replace('.', '/') + ".class"] = cp
                } else {
                    log("skipping entry: %s", entry.name)
                    jis.closeEntry()
                }
            } catch (e: IOException) {
                throw RuntimeException(e)
            }
        } while (true)
        log("Read %d binary patches", this.patches.size)
        log("Patch list :\n\t%s", Joiner.on("\n\t").join(this.patches.entries))
    }

    @Throws(IOException::class)
    private fun readPatch(patchEntry: JarEntry, jis: JarInputStream): ClassPatch {
        log("\t%s", patchEntry.name)
        val input = ByteStreams.newDataInput(jis.readBytes())
        val name = input.readUTF()
        val sourceClassName = input.readUTF()
        val targetClassName = input.readUTF()
        val exists = input.readBoolean()
        var inputChecksum = 0
        if (exists) {
            inputChecksum = input.readInt()
        }
        val patchLength = input.readInt()
        val patchBytes = ByteArray(patchLength)
        input.readFully(patchBytes)
        return ClassPatch(name, sourceClassName, targetClassName, exists, inputChecksum, patchBytes)
    }

    private fun log(format: String, vararg args: Any) {
        project.logger.info(String.format(format, *args))
    }

    data class ClassPatch(
        val name: String,
        val sourceClassName: String,
        val targetClassName: String,
        val existsAtTarget: Boolean,
        val inputChecksum: Int,
        val patch: ByteArray
    ) {
        override fun toString(): String {
            return String.format(
                "%s : %s => %s (%b) size %d", name, sourceClassName, targetClassName, existsAtTarget, patch.size
            )
        }
    }

    companion object {
        private fun adlerHash(input: ByteArray): Int {
            val hasher = Adler32()
            hasher.update(input, 0, input.size)
            return hasher.value.toInt()
        }
    }
}