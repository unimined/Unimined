package xyz.wagyourtail.unimined.mapping.processors

import xyz.wagyourtail.unimined.api.mapping.MappingContext
import xyz.wagyourtail.unimined.api.mapping.MappingData
import xyz.wagyourtail.unimined.api.mapping.MappingPostProcessor
import xyz.wagyourtail.unimined.api.mapping.view
import xyz.wagyourtail.unimined.api.util.uniminedLogger
import xyz.wagyourtail.unimined.mapping.MappingTree
import xyz.wagyourtail.unimined.mapping.MutableMappingTree
import xyz.wagyourtail.unimined.mapping.Namespace
import xyz.wagyourtail.unimined.mapping.element.MetadataElement
import xyz.wagyourtail.unimined.mapping.element.mapping.MutableClassMapping
import xyz.wagyourtail.unimined.mapping.element.mapping.MutableFieldMapping
import xyz.wagyourtail.unimined.mapping.element.mapping.MutableMethodMapping
import xyz.wagyourtail.unimined.mapping.element.mapping.MutableParameterMapping
import xyz.wagyourtail.unimined.mapping.element.metadata.AnnotationElement
import xyz.wagyourtail.unimined.mapping.element.metadata.JavadocElement
import xyz.wagyourtail.unimined.mapping.element.metadata.MutableMetadataParent

/**
 * A [MappingPostProcessor] layering metadata-only data — javadocs and
 * annotations — onto the composed tree: each overlay [MappingData] is
 * matched to tree rows by the overlay's own source namespace (its first
 * namespace), and matching rows receive the overlay's
 * [JavadocElement]/[AnnotationElement] metadata they don't already
 * carry. This is the "annotation/javadoc layers" builtin — e.g.
 * mod-provided javadocs merge comments into the mapping bundle without
 * touching names.
 *
 * Not a ServiceLoader default: the processor is meaningless without
 * overlays, so hosts register configured instances via
 * `MappingExtension.postProcessors.register(...)`. Name cells and descs
 * are never modified; unmatched overlay rows are skipped.
 *
 * @param overlays the overlay data, applied in order — later overlays
 *  add metadata earlier ones didn't supply (no overwrite: rows keep the
 *  first javadoc/annotation value they got)
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
            mergeTree(tree, overlayTree, srcNs)
        }
        return data
    }

    private fun mergeTree(
        tree: MutableMappingTree,
        overlay: xyz.wagyourtail.unimined.mapping.MappingTree,
        srcNs: Namespace
    ) {
        for (overlayCls in overlay.classes) {
            val name = overlayCls.names[srcNs] ?: continue
            val target = tree.getClass(srcNs, name) as? MutableClassMapping ?: continue
            target.mergeMetadata(overlayCls.metadata)
            for (overlayMethod in overlayCls.methods) {
                val entry = overlayMethod.entries[srcNs] ?: continue
                val targetMethod = target.methods
                    .filterIsInstance<MutableMethodMapping>()
                    .firstOrNull {
                        val e = it.entries[srcNs] ?: return@firstOrNull false
                        e.name == entry.name && e.descriptor == entry.descriptor
                    } ?: continue
                targetMethod.mergeMetadata(overlayMethod.metadata)
                val targetParams = targetMethod.parameters.toList()
                for (overlayParam in overlayMethod.parameters) {
                    val targetParam = targetParams.firstOrNull {
                        it.index == overlayParam.index && it.lvOrd == overlayParam.lvOrd
                    } as? MutableParameterMapping ?: continue
                    targetParam.mergeMetadata(overlayParam.metadata)
                }
            }
            for (overlayField in overlayCls.fields) {
                val entry = overlayField.entries[srcNs] ?: continue
                val targetField = target.fields
                    .filterIsInstance<MutableFieldMapping>()
                    .firstOrNull {
                        val e = it.entries[srcNs] ?: return@firstOrNull false
                        e.name == entry.name && e.descriptor == entry.descriptor
                    } ?: continue
                targetField.mergeMetadata(overlayField.metadata)
            }
        }
    }

    /**
     * Copy overlay [JavadocElement]/[AnnotationElement] the row lacks:
     * a row with a javadoc already keeps it (first layer wins — a second
     * doc element would render as duplicate comments), while annotations
     * merge by value since several distinct annotations are legitimate.
     */
    private fun MutableMetadataParent.mergeMetadata(overlayMeta: Iterable<MetadataElement>) {
        var hasJavadoc = metadata.any { it is JavadocElement }
        val annotations = metadata.filterIsInstance<AnnotationElement>()
            .mapTo(mutableSetOf()) { it.annotation.toString() }
        for (meta in overlayMeta) {
            when (meta) {
                is JavadocElement -> if (!hasJavadoc) {
                    addMetadata(meta); hasJavadoc = true
                }

                is AnnotationElement -> if (annotations.add(meta.annotation.toString())) {
                    addMetadata(meta)
                }

                else -> {}
            }
        }
    }

}
