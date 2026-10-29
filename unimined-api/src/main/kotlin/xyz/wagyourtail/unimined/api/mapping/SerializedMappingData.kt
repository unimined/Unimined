package xyz.wagyourtail.unimined.api.mapping

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.util.*
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.io.path.outputStream

/**
 * File-backed [MappingData] bundle: a zip carrying a `views.json` manifest
 * plus one payload per contributing [MappingViewSerializer]. Unlike the
 * row-only [MappingBundle], payloads preserve rich view kinds (UML trees
 * with metadata, unpick groups, ...) so `needsMappingTree` steps keep the
 * views they actually consume through the configuration-cache-safe path.
 *
 * `unimined-access` payloads may appear in legacy bundles; unknown ids
 * are skipped on read.
 */
object SerializedMappingData {

    @Serializable
    private class Manifest(val version: Int = 1, val payloads: List<String>)

    private val json = Json {
        prettyPrint = false
        // metadata impls own a `type` property (e.g. InnerClassElementData) —
        // the default "type" class discriminator collides with it
        classDiscriminator = "#type"
    }

    private fun serializers(): List<MappingViewSerializer> {
        val found = ServiceLoader.load(
            MappingViewSerializer::class.java,
            MappingViewSerializer::class.java.classLoader
        ).toList() + ServiceLoader.load(
            MappingViewSerializer::class.java,
            Thread.currentThread().contextClassLoader
        ).toList()
        return found.distinctBy { it.id }
    }

    private fun serializer(id: String): MappingViewSerializer? =
        serializers().firstOrNull { it.id == id }

    /**
     * Persist every view family some registered serializer supports.
     *
     * @return the serializer ids written — empty when no serializer could
     *  handle any of [data]'s views (caller should fall back to a row
     *  [MappingBundle] or fail)
     */
    /** DOS epoch — the minimum representable zip timestamp; used
     *  so bundles are byte-reproducible across rebuilds. */
    private const val DOS_EPOCH: Long = 315532800000

    fun write(data: MappingData, path: Path): List<String> {
        val payloads = serializers().mapNotNull { s ->
            if (!data.viewTypes.containsAll(s.views)) return@mapNotNull null
            (s.write(data))?.let { s.id to it }
        }
        if (payloads.isEmpty()) return emptyList()
        ZipOutputStream(path.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("views.json").apply { time = DOS_EPOCH })
            zip.write(
                json.encodeToString(Manifest(payloads = payloads.map { it.first }))
                    .encodeToByteArray()
            )
            zip.closeEntry()
            for ((id, bytes) in payloads) {
                zip.putNextEntry(ZipEntry("views/$id").apply { time = DOS_EPOCH })
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return payloads.map { it.first }
    }

    /** Rehydrate a serialized bundle into [MappingData]. */
    fun read(path: Path): MappingData {
        val views = linkedMapOf<kotlin.reflect.KClass<*>, Any>()
        ZipFile(path.toFile()).use { zip ->
            val manifest = zip.getEntry("views.json")?.let { e ->
                json.decodeFromString<Manifest>(zip.getInputStream(e).readBytes().decodeToString())
            } ?: throw IllegalArgumentException("$path is not a SerializedMappingData bundle")
            for (id in manifest.payloads) {
                val serializer = serializer(id) ?: continue
                val payload = zip.getEntry("views/$id")?.let { zip.getInputStream(it).readBytes() }
                    ?: continue
                val fragment = serializer.read(payload)
                for (type in fragment.viewTypes) {
                    views.putIfAbsent(type, fragment.view(type.java)!!)
                }
            }
        }
        return ViewMapMappingData(views)
    }

    private val ZIP_MAGIC = byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 3, 4)

    /**
     * Read [path] as a [SerializedMappingData] bundle when it is a zip, or
     * as a legacy row [MappingBundle] otherwise — the single entry point
     * for mapping-file inputs so either format works transparently.
     */
    fun readEither(path: Path): MappingData {
        val head = ByteArray(4)
        Files.newInputStream(path).use { it.readNBytes(head, 0, 4) }
        return if (head.contentEquals(ZIP_MAGIC)) read(path)
        else BundleMappingData(MappingBundle.read(path))
    }

}
