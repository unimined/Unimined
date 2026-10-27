package xyz.wagyourtail.unimined.mapping.serialization

import kotlin.reflect.KClass
import kotlinx.serialization.json.Json
import xyz.wagyourtail.unimined.api.mapping.MappingData
import xyz.wagyourtail.unimined.api.mapping.MappingDataView
import xyz.wagyourtail.unimined.api.mapping.MappingViewSerializer
import xyz.wagyourtail.unimined.api.mapping.UmlMappingData
import xyz.wagyourtail.unimined.api.mapping.UmlMappingTreeView
import xyz.wagyourtail.unimined.api.mapping.view
import xyz.wagyourtail.unimined.mapping.serialization.SerializableMappingTree

/**
 * Bundled [MappingViewSerializer] persisting [UmlMappingTreeView] through
 * UML's flat-array [SerializableMappingTree] wire. The rehydrated fragment
 * is a full [UmlMappingData], so one payload restores both the rich UML
 * view and the portable [xyz.wagyourtail.unimined.api.mapping.MappingTree]
 * view.
 */
class UmlMappingTreeSerializer : MappingViewSerializer {

    override val id = "uml-tree"

    override val views: Set<KClass<out MappingDataView>> =
        setOf(UmlMappingTreeView::class)

    override fun write(data: MappingData): ByteArray? {
        val tree = data.view<UmlMappingTreeView>()?.tree ?: return null
        return Json.encodeToString(
            SerializableMappingTree.serializer(),
            SerializableMappingTree.of(tree)
        ).encodeToByteArray()
    }

    override fun read(payload: ByteArray): MappingData =
        UmlMappingData(
            Json.decodeFromString(
                SerializableMappingTree.serializer(),
                payload.decodeToString()
            ).toTree()
        )

}
