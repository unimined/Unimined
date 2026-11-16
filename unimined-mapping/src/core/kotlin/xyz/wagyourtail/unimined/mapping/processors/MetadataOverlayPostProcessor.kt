package xyz.wagyourtail.unimined.mapping.processors

import xyz.wagyourtail.unimined.api.mapping.MappingContext
import xyz.wagyourtail.unimined.api.mapping.MappingData
import xyz.wagyourtail.unimined.api.mapping.MappingPostProcessor
import xyz.wagyourtail.unimined.api.mapping.view
import xyz.wagyourtail.unimined.api.util.uniminedLogger
import xyz.wagyourtail.unimined.mapping.MappingTree
import xyz.wagyourtail.unimined.mapping.MutableMappingTree
import xyz.wagyourtail.unimined.mapping.tree.impl.mergeMetadataOverlay

/**
 * A [MappingPostProcessor] layering metadata-only data — javadocs and
 * annotations — onto the composed tree: each overlay [MappingData] is
 * matched to tree rows by the overlay's own source namespace (its first
 * namespace), and matching rows receive the overlay's
 * `JavadocElement`/`AnnotationElement` metadata they don't already
 * carry. This is the "annotation/javadoc layers" builtin — e.g.
 * mod-provided javadocs merge comments into the mapping bundle without
 * touching names. Delegates to UML's [mergeMetadataOverlay]: javadocs
 * keep the first layer's value, annotations merge by value; name cells
 * and descs are never modified.
 *
 * Not a ServiceLoader default: the processor is meaningless without
 * overlays, so hosts register configured instances via
 * `MappingExtension.postProcessors.register(...)`.
 *
 * @param overlays the overlay data, applied in order — later overlays
 *  add metadata earlier ones didn't supply
 */
class MetadataOverlayPostProcessor(
    private val overlays: List<MappingData> = emptyList()
) : MappingPostProcessor {

    override val name = "metadata-overlay"

    constructor(vararg overlays: MappingData) : this(overlays.toList())

    override fun process(data: MappingData, ctx: MappingContext): MappingData {
        val tree = data.view<MappingTree>() as? MutableMappingTree
            ?: return data
        for (overlay in overlays) {
            val overlayTree = overlay.view<MappingTree>()
            if (overlayTree == null) {
                uniminedLogger(ctx.environmentId, ctx.logger).subLogger("mappings").info(
                    "$name: an overlay exposes no UML MappingTree view; skipping it"
                )
                continue
            }
            val srcNs = overlayTree.namespaces.firstOrNull()
            if (srcNs == null || srcNs !in tree.namespaces) {
                uniminedLogger(ctx.environmentId, ctx.logger).subLogger("mappings").info(
                    "$name: overlay base namespace " +
                            "'${srcNs?.name}' is not in the composed tree " +
                            "(${tree.namespaces.joinToString { it.name }}); skipping"
                )
                continue
            }
            tree.mergeMetadataOverlay(overlayTree, srcNs)
        }
        return data
    }

}
