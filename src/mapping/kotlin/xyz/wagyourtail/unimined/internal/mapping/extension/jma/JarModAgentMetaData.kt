package xyz.wagyourtail.unimined.internal.mapping.extension.jma

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import xyz.wagyourtail.unimined.internal.mapping.extension.MixinRemapExtension
import xyz.wagyourtail.unimined.util.readZipInputStreamFor
import java.nio.file.FileSystem
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.jar.Manifest
import kotlin.io.path.inputStream
import kotlin.io.path.outputStream
import kotlin.io.path.writeText

class JarModAgentMetaData(parent: MixinRemapExtension) : MixinRemapExtension.MixinMetadata(parent) {
    private val GSON = GsonBuilder().setPrettyPrinting().create()
    private val classesToRefmap = mutableMapOf<String, String>()
    private val refmaps = mutableMapOf<String, TreeMap<String, Any>>()
    private val existingRefmaps = mutableMapOf<String, JsonObject>()

    override fun readInput(vararg input: Path): CompletableFuture<*> {
        val futures = mutableListOf<CompletableFuture<*>>()
        for (i in input) {
            futures.add(readSingleInput(i))
        }
        return CompletableFuture.allOf(*futures.toTypedArray())
    }

    private fun readSingleInput(input: Path): CompletableFuture<*> {
        return CompletableFuture.runAsync {
            val transforms = mutableListOf<String>()
            val refmaps = mutableListOf<String>()
            input.readZipInputStreamFor("META-INF/MANIFEST.MF", false) {
                val manifest = Manifest(it)
                val jarmodTransforms = manifest.mainAttributes.getValue("JarModAgent-Transforms")
                val jarmodRefmaps = manifest.mainAttributes.getValue("JarModAgent-Refmaps")
                if (jarmodTransforms != null) {
                    transforms.addAll(jarmodTransforms.split(" "))
                }
                if (jarmodRefmaps != null) {
                    refmaps.addAll(jarmodRefmaps.split(" "))
                }
            }
            parent.logger.info("[PreRead] Found JarModAgent transforms: $transforms")
            parent.logger.info("[PreRead] Found JarModAgent refmaps: $refmaps")
            for (transform in transforms) {
                val refmapName = transform.substringBeforeLast(".") + "-refmap.json"
                this.refmaps.computeIfAbsent(refmapName) { TreeMap() }
                input.readZipInputStreamFor(transform, false) {
                    it.bufferedReader().use { reader ->
                        for (line in reader.lines()) {
                            if (classesToRefmap.containsKey(line) && classesToRefmap[line] != refmapName) {
                                parent.logger.warn("[PreRead] $line already has a refmap entry!")
                                parent.logger.warn("[PreRead] ${classesToRefmap[line]} != $refmapName")
                                parent.logger.warn("[PreRead] Will only read/write to ${classesToRefmap[line]} for $line")
                                continue
                            }
                            classesToRefmap[line] = refmapName
                            parent.logger.info("[PreRead] Added $line to $refmapName")
                        }
                    }
                }
            }
            for (refmap in refmaps) {
                input.readZipInputStreamFor(refmap, false) {
                    try {
                        val json = JsonParser.parseReader(it.reader()).asJsonObject
                        this.existingRefmaps[refmap] = json
                        parent.logger.info("[PreRead] Found refmap $refmap")
                    } catch (e: Exception) {
                        parent.logger.error("[PreRead] Failed to parse refmap $refmap: ${e.message}")
                    }
                }
            }

        }
    }

    override fun contains(className: String): Boolean {
        return classesToRefmap.containsKey(className)
    }

    override fun getRefmapFor(className: String): TreeMap<String, Any> {
        return refmaps[classesToRefmap[className]]!!
    }

    override fun getExistingRefmapFor(className: String): JsonObject? {
        return existingRefmaps[classesToRefmap[className]]
    }

    override fun writeExtra(fs: FileSystem) {
        if (refmaps.isNotEmpty()) {
            val manifest = Manifest(fs.getPath("META-INF/MANIFEST.MF").inputStream())

            if (!parent.noRefmap.contains("JarModAgent")) {
                manifest.mainAttributes.putValue("JarModAgent-Refmaps", refmaps.keys.joinToString(" "))
            } else {
                manifest.mainAttributes.remove("JarModAgent-Refmaps")
            }

            fs.getPath("META-INF/MANIFEST.MF")
                .outputStream(StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.CREATE).use {
                manifest.write(it)
            }

            for ((name, json) in refmaps) {
                fs.getPath(name).writeText(
                    GSON.toJson(json),
                    Charsets.UTF_8,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.CREATE
                )
            }
        }
    }

    override fun fallbackRefmap(): TreeMap<String, Any> {
        return refmaps.values.first()
    }

}
