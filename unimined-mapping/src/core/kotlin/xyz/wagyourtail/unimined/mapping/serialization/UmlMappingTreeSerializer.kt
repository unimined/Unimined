package xyz.wagyourtail.unimined.mapping.serialization

import okio.Buffer
import xyz.wagyourtail.commonskt.reader.StringCharReader
import xyz.wagyourtail.unimined.api.mapping.MappingData
import xyz.wagyourtail.unimined.api.mapping.MappingViewSerializer
import xyz.wagyourtail.unimined.api.mapping.UmlMappingData
import xyz.wagyourtail.unimined.api.mapping.view
import xyz.wagyourtail.unimined.mapping.MappingTree
import xyz.wagyourtail.unimined.mapping.MutableMappingTree
import xyz.wagyourtail.unimined.mapping.formats.FormatReaderSettings
import xyz.wagyourtail.unimined.mapping.formats.umf.UMFB
import xyz.wagyourtail.unimined.mapping.formats.umf.UMFReader
import xyz.wagyourtail.unimined.mapping.sink.emit
import kotlin.reflect.KClass

/**
 * Bundled [MappingViewSerializer] persisting the UML [MappingTree] as a
 * `UMFB` binary event stream — the machine-to-machine sibling of the UMF
 * text format. Same element universe and lossless tree round-trip, but
 * reading skips tokenization, escaping, and JVMS re-validation, which is
 * the dominant cost of hydrating a large composed tree.
 *
 * Reading dispatches on the `UMFB` magic so bundles written before the
 * switch (UMF text payloads) still hydrate.
 */
class UmlMappingTreeSerializer : MappingViewSerializer {

    override val id = "uml-tree"

    override val views: Set<KClass<*>> = setOf(MappingTree::class)

    override fun write(data: MappingData): ByteArray? {
        val tree = data.view<MappingTree>() ?: return null
        val buf = Buffer()
        tree.emit(UMFB.write(buf), sort = true)
        return buf.readByteArray()
    }

    override fun read(payload: ByteArray): MappingData {
        // payload came from tree.emit — rows are already canonical
        val tree = MutableMappingTree.canonical()
        if (payload.size >= 4 && payload[0] == 'U'.code.toByte() && payload[1] == 'M'.code.toByte()
            && payload[2] == 'F'.code.toByte() && payload[3] == 'B'.code.toByte()
        ) {
            UMFB.read(payload, tree)
        } else {
            UMFReader.readNonBlocking(
                // machine-written payload — skip JVMS syntax revalidation
                FormatReaderSettings(unchecked = true),
                input = StringCharReader(payload.decodeToString().replace("\r", "")),
                context = null,
                into = tree,
                nsMapping = emptyMap()
            )
        }
        return UmlMappingData(tree)
    }

}
