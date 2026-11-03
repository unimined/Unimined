package xyz.wagyourtail.unimined.mapping

import xyz.wagyourtail.unimined.api.mapping.*
import xyz.wagyourtail.unimined.api.util.uniminedLogger
import xyz.wagyourtail.unimined.api.util.ProgressGate
import xyz.wagyourtail.unimined.mapping.element.MappingElement
import xyz.wagyourtail.unimined.mapping.element.MetadataElement
import xyz.wagyourtail.unimined.mapping.element.metadata.MetadataParent
import xyz.wagyourtail.unimined.mapping.internal.CountingMappingSink
import xyz.wagyourtail.unimined.mapping.sink.emit
import xyz.wagyourtail.unimined.mapping.tree.impl.fillMissingNames
import xyz.wagyourtail.unimined.mapping.tree.impl.renest
import kotlin.reflect.KClass

/**
 * The host fallback composer: merges ordered per-source [MappingData] into
 * one environment MappingData.
 *
 * Every part must expose the canonical UML [MappingTree] view — UML is
 * the mapping model, so there is no longer an adaptation path for
 * alternate tree shapes. Trees merge by emitting each part's tree into
 * one [MutableMappingTree] in declaration order — the same matching-name
 * merge UML's own resolver uses (a part whose base namespace was provided
 * by an earlier part chains onto it). Non-tree view kinds supplied by
 * multiple parts resolve last-declaration-wins, preserving each supported
 * view rather than flattening into rename rows.
 */
class DefaultMappingComposer : MappingComposer {

    override val name = "default"

    override fun supports(spec: MappingComposeRequest): Boolean =
        spec.parts.all { it.viewTypes.contains(MappingTree::class) }

    override fun compose(request: MappingComposeRequest, ctx: MappingContext): MappingData {
        if (request.parts.isEmpty()) return ViewMapMappingData(emptyMap())
        if (request.parts.size == 1) return request.parts.single()

        val trees = request.parts.mapIndexed { index, part ->
            part.view<MappingTree>() ?: throw IllegalStateException(
                "cannot compose mapping source ${index + 1} of " +
                        "${request.parts.size} in '${ctx.environmentId}': it does " +
                        "not expose a UML MappingTree view — declare a source the " +
                        "UML MappingProvider can resolve or supply a custom " +
                        "MappingComposer"
            )
        }

        val composed = MutableMappingTree()
        // merge pass can take tens of seconds on real bundles — count rows
        // so the log shows progress instead of stalling. total counts every
        // element emit() pushes: contents subtrees + attached metadata
        // (inner classes etc. are pushed as MetadataElement rows)
        fun metaCount(m: MetadataElement): Long =
            1L + ((m as? MetadataParent)?.metadata ?: emptyList()).sumOf(::metaCount)
        fun elCount(e: MappingElement): Long =
            1L + e.contents.sumOf(::elCount) +
                ((e as? MetadataParent)?.metadata ?: emptyList()).sumOf(::metaCount)
        val total = trees.sumOf { tree ->
            tree.packages.sumOf(::elCount) + tree.classes.sumOf(::elCount) +
                tree.constantGroups.sumOf(::elCount)
        }
        val slog = uniminedLogger(ctx.environmentId, ctx.logger).subLogger("mappings")
        val gate = ProgressGate { elapsed, done ->
            slog.info {
                "composing: $done/$total rows (${done * 100 / total.coerceAtLeast(1)}%) in ${elapsed}ms"
            }
        }
        slog.lifecycle(
            "composing ${trees.size} mapping parts ($total rows)"
        )
        val counted = CountingMappingSink(composed, gate)
        for (tree in trees) tree.emit(counted)

        // relocate inner classes their source namespace left unmapped under
        // the mapped parent (Outer$1 -> mapped/Outer$1) — verbatim name fill
        // would strand them in the source package and break javac nests
        for (tree in trees) {
            val srcNs = tree.namespaces.firstOrNull() ?: continue
            composed.renest(srcNs, tree.namespaces.drop(1).toSet())
        }

        // fill missing namespace names along each part's hop chain —
        // a member unmapped by a later source keeps its parent hop's name
        // (yarn-unmapped members stay `field_*`, not `official`; srg-era
        // class names in `searge` propagate to `mcp`, not `official`).
        // Each target namespace is filled by the first part providing it.
        val filled = mutableSetOf<Namespace>()
        val toFill = mutableListOf<Pair<Namespace, Set<Namespace>>>()
        for (tree in trees) {
            for ((parent, target) in tree.namespaces.zipWithNext()) {
                if (target !in filled) {
                    toFill += parent to setOf(target)
                    filled += target
                }
            }
        }
        composed.fillMissingNames(*toFill.toTypedArray())

        val views = linkedMapOf<KClass<*>, Any>(
            MappingTree::class to composed
        )
        for (part in request.parts) {
            for (type in part.viewTypes) {
                if (type == MappingTree::class) continue
                val previous = views.put(type, part.view(type.java)!!)
                if (previous != null) {
                    uniminedLogger(ctx.environmentId, ctx.logger).subLogger("mappings").info(
                        "view ${type.simpleName} supplied by " +
                                "multiple sources; using the last declaration"
                    )
                }
            }
        }
        // metadata merges property-wise rather than last-wins, so every
        // source's version/channel keys survive composition
        val properties = request.parts.fold(emptyMap<String, String>()) { acc, part ->
            acc + part.properties
        }
        return ViewMapMappingData(views, properties)
    }

}
