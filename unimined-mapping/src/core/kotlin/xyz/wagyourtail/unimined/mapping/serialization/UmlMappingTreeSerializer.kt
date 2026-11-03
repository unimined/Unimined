package xyz.wagyourtail.unimined.mapping.serialization

import xyz.wagyourtail.commonskt.reader.StringCharReader
import xyz.wagyourtail.unimined.api.mapping.MappingData
import xyz.wagyourtail.unimined.api.mapping.MappingViewSerializer
import xyz.wagyourtail.unimined.api.mapping.UmlMappingData
import xyz.wagyourtail.unimined.api.mapping.view
import xyz.wagyourtail.unimined.mapping.MappingTree
import xyz.wagyourtail.unimined.mapping.MutableMappingTree
import xyz.wagyourtail.unimined.mapping.formats.umf.UMFReader
import xyz.wagyourtail.unimined.mapping.formats.umf.UMFWriter
import xyz.wagyourtail.unimined.mapping.sink.emit
import kotlin.reflect.KClass

/**
 * Bundled [MappingViewSerializer] persisting the UML [MappingTree] as a
 * UMF document — UML's own lossless format preserves everything a tree
 * carries (`AccessElement`s, signatures, comments, constant groups,
 * metadata) through the structured writer/reader pipeline rather than a
 * bespoke DTO encoding.
 */
class UmlMappingTreeSerializer : MappingViewSerializer {

    override val id = "uml-tree"

    override val views: Set<KClass<*>> = setOf(MappingTree::class)

    override fun write(data: MappingData): ByteArray? {
        val tree = data.view<MappingTree>() ?: return null
        return buildString {
            tree.emit(
                UMFWriter.write({ append(it) }, minimize = true),
                sort = true
            )
        }.encodeToByteArray()
    }

    override fun read(payload: ByteArray): MappingData {
        val tree = MutableMappingTree()
        UMFReader.readNonBlocking(
            input = StringCharReader(payload.decodeToString().replace("\r", "")),
            context = null,
            into = tree,
            nsMapping = emptyMap()
        )
        return UmlMappingData(tree)
    }

}
