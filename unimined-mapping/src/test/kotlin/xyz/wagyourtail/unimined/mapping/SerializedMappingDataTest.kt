package xyz.wagyourtail.unimined.mapping

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import xyz.wagyourtail.unimined.api.mapping.*
import kotlin.io.path.createTempFile
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists

/**
 * Round-trips [SerializedMappingData] bundles: UML views survive through
 * the zip payload, and [SerializedMappingData.readEither] transparently
 * accepts both the zip and the legacy row [MappingBundle].
 */
class SerializedMappingDataTest {

    private val tmp = mutableListOf<java.nio.file.Path>()

    @AfterEach
    fun cleanup() = tmp.forEach { it.deleteIfExists() }

    private fun tempPath() = createTempFile("unimined-bundle-", ".bin").also(tmp::add)

    /** A two-namespace UML tree with a class carrying a field and method. */
    private fun umlData(): UmlMappingData = UmlMappingData(
        mappingTree("official", "named") {
            clazz("a/A", "b/B") {
                method("m_1;()V", "doThing")
                field("f_1;I", "counter")
            }
        }
    )

    /** [MappingData] exposing no views a serializer can cover. */
    private fun portableOnlyData(): MappingData = ViewMapMappingData(emptyMap())

    /** Emits the `official`→`named` rows as a comparison fingerprint. */
    private fun fingerprint(data: MappingData): String =
        MappingBundle.capture("official", "named", data.view<MappingTree>()!!).toJson()

    @Test
    fun `uml view round-trips through serialized bundle`() {
        val data = umlData()
        val path = tempPath()

        val written = SerializedMappingData.write(data, path)
        assertEquals(listOf("uml-tree"), written)
        assertTrue(path.exists())

        val restored = SerializedMappingData.read(path)
        assertNotNull(
            restored.view<MappingTree>(),
            "rehydrated bundle must expose the UML tree view"
        )
        assertEquals(fingerprint(data), fingerprint(restored))
    }

    @Test
    fun `readEither accepts serialized zip`() {
        val path = tempPath()
        SerializedMappingData.write(umlData(), path)

        val restored = SerializedMappingData.readEither(path)
        assertNotNull(restored.view<MappingTree>())
        assertEquals(fingerprint(umlData()), fingerprint(restored))
    }

    @Test
    fun `readEither accepts legacy row bundle`() {
        val path = tempPath()
        val data = umlData()
        MappingBundle.capture("official", "named", data.view<MappingTree>()!!).write(path)

        val restored = SerializedMappingData.readEither(path)
        assertNotNull(
            restored.view<MappingTree>(),
            "row bundles rehydrate into a UML tree"
        )
        assertEquals(fingerprint(data), fingerprint(restored))
    }

    @Test
    fun `write returns empty when no serializer covers the views`() {
        val path = tempPath()
        val portableOnly = portableOnlyData()
        assertEquals(emptyList<String>(), SerializedMappingData.write(portableOnly, path))
    }

}
