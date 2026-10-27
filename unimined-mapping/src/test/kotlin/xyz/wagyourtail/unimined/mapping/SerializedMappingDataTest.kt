package xyz.wagyourtail.unimined.mapping

import kotlin.reflect.KClass
import kotlin.io.path.createTempFile
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import xyz.wagyourtail.unimined.api.mapping.MappingBundle
import xyz.wagyourtail.unimined.api.mapping.MappingData
import xyz.wagyourtail.unimined.api.mapping.MappingTree
import xyz.wagyourtail.unimined.api.mapping.SerializedMappingData
import xyz.wagyourtail.unimined.api.mapping.UmlMappingData
import xyz.wagyourtail.unimined.api.mapping.UmlMappingTreeView
import xyz.wagyourtail.unimined.api.mapping.view
import xyz.wagyourtail.unimined.api.mapping.MappingDataView
import xyz.wagyourtail.unimined.api.mapping.MappingVisitor
import xyz.wagyourtail.unimined.api.mapping.ViewMapMappingData
import xyz.wagyourtail.unimined.mapping.MutableMappingTree
import xyz.wagyourtail.unimined.mapping.Namespace
import xyz.wagyourtail.unimined.mapping.jvms.ext.FieldNameAndDescriptor
import xyz.wagyourtail.unimined.mapping.jvms.ext.MethodNameAndDescriptor
import xyz.wagyourtail.unimined.mapping.jvms.four.two.one.InternalName
import xyz.wagyourtail.unimined.mapping.jvms.four.two.two.UnqualifiedName

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
    private fun umlData(): UmlMappingData {
        val official = Namespace("official")
        val named = Namespace("named")
        val tree = MutableMappingTree().apply {
            addNamespaces(listOf(official, named))
        }
        val cls = tree.clazz(mapOf(
            official to InternalName.unchecked("a/A"),
            named to InternalName.unchecked("b/B")
        ))
        cls.method(mapOf(
            official to MethodNameAndDescriptor.unchecked("m_1;()V")
        )).setNames(mapOf(named to UnqualifiedName.unchecked("doThing")))
        cls.field(mapOf(
            official to FieldNameAndDescriptor.unchecked("f_1;I")
        )).setNames(mapOf(named to UnqualifiedName.unchecked("counter")))
        return UmlMappingData(tree)
    }

    /** Minimal portable-view-only [MappingData] with no UML payload. */
    private fun portableOnlyData(): MappingData {
        val tree = object : MappingTree {
            override val namespaces = listOf("a", "b")
            override fun accept(sourceNamespace: String, targetNamespace: String, visitor: MappingVisitor) {}
        }
        return ViewMapMappingData(mapOf<KClass<out MappingDataView>, MappingDataView>(
            MappingTree::class to tree))
    }

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
        assertNotNull(restored.view<UmlMappingTreeView>(),
            "rehydrated bundle must expose the UML view")
        assertNotNull(restored.view<MappingTree>(),
            "rehydrated UML payload must also expose the portable view")
        assertEquals(fingerprint(data), fingerprint(restored))
    }

    @Test
    fun `readEither accepts serialized zip`() {
        val path = tempPath()
        SerializedMappingData.write(umlData(), path)

        val restored = SerializedMappingData.readEither(path)
        assertNotNull(restored.view<UmlMappingTreeView>())
        assertEquals(fingerprint(umlData()), fingerprint(restored))
    }

    @Test
    fun `readEither accepts legacy row bundle`() {
        val path = tempPath()
        val data = umlData()
        MappingBundle.capture("official", "named", data.view<MappingTree>()!!).write(path)

        val restored = SerializedMappingData.readEither(path)
        assertNull(restored.view<UmlMappingTreeView>(),
            "row bundles carry no UML view")
        assertEquals(fingerprint(data), fingerprint(restored))
    }

    @Test
    fun `write returns empty when no serializer covers the views`() {
        val path = tempPath()
        val portableOnly = portableOnlyData()
        assertEquals(emptyList<String>(), SerializedMappingData.write(portableOnly, path))
    }

}
