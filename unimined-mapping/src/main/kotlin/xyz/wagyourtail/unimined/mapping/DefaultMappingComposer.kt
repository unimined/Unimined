package xyz.wagyourtail.unimined.mapping

import org.gradle.api.GradleException
import xyz.wagyourtail.unimined.api.mapping.MappingComposeRequest
import xyz.wagyourtail.unimined.api.mapping.MappingComposer
import xyz.wagyourtail.unimined.api.mapping.MappingContext
import xyz.wagyourtail.unimined.api.mapping.MappingData
import xyz.wagyourtail.unimined.api.mapping.MappingDataView
import xyz.wagyourtail.unimined.api.mapping.MappingTree
import xyz.wagyourtail.unimined.api.mapping.UmlMappingData
import xyz.wagyourtail.unimined.api.mapping.UmlMappingTreeView
import xyz.wagyourtail.unimined.api.mapping.ViewMapMappingData
import xyz.wagyourtail.unimined.api.mapping.view
import xyz.wagyourtail.unimined.mapping.MutableMappingTree
import xyz.wagyourtail.unimined.mapping.Namespace
import xyz.wagyourtail.unimined.mapping.sink.emit
import xyz.wagyourtail.unimined.mapping.tree.impl.fillMissingNames
import xyz.wagyourtail.unimined.mapping.tree.impl.renest
import kotlin.reflect.KClass

/**
 * The host fallback composer: merges ordered per-source [MappingData] into
 * one environment MappingData.
 *
 * Tree views merge by emitting each part's UML tree into one
 * [MutableMappingTree] in declaration order — the same matching-name merge
 * UML's own resolver uses (a part whose base namespace was provided by an
 * earlier part chains onto it). Non-tree view kinds supplied by multiple
 * parts resolve last-declaration-wins, preserving each supported view
 * rather than flattening into rename rows.
 */
class DefaultMappingComposer : MappingComposer {

    override val name = "default"

    override fun supports(spec: MappingComposeRequest): Boolean =
        spec.parts.size <= 1 ||
            spec.parts.all { it.viewTypes.contains(UmlMappingTreeView::class) }

    override fun compose(request: MappingComposeRequest, ctx: MappingContext): MappingData {
        if (request.parts.isEmpty()) return ViewMapMappingData(emptyMap())
        if (request.parts.size == 1) return request.parts.single()

        val trees = request.parts.mapIndexed { index, part ->
            part.view<UmlMappingTreeView>()?.tree ?: throw GradleException(
                "cannot compose mapping source ${index + 1} of " +
                    "${request.parts.size} in '${ctx.environmentId}': it does " +
                    "not expose a UmlMappingTreeView — composing portable-only " +
                    "or mixed-view sources requires a view adapter (pending) " +
                    "or a custom MappingComposer"
            )
        }

        val composed = MutableMappingTree()
        for (tree in trees) tree.emit(composed)

        // relocate inner classes their source namespace left unmapped under
        // the mapped parent (Outer$1 -> mapped/Outer$1) — verbatim name fill
        // would strand them in the source package and break javac nests
        for (tree in trees) {
            val srcNs = tree.namespaces.firstOrNull() ?: continue
            composed.renest(srcNs, tree.namespaces.drop(1).toSet())
        }

        // fill missing namespace names from each part's base namespace —
        // a member unmapped by a later source keeps its parent hop's name
        // (yarn-unmapped members stay `field_*`, not `official`). Each
        // target namespace is filled by the first part providing it.
        val filled = mutableSetOf<Namespace>()
        val toFill = mutableListOf<Pair<Namespace, Set<Namespace>>>()
        for (tree in trees) {
            val targets = tree.namespaces.drop(1).toSet() - filled
            if (targets.isNotEmpty()) {
                toFill += tree.namespaces.first() to targets
                filled += targets
            }
        }
        composed.fillMissingNames(*toFill.toTypedArray())

        val data = UmlMappingData(composed)
        val views = linkedMapOf<KClass<out MappingDataView>, MappingDataView>(
            UmlMappingTreeView::class to data.view(UmlMappingTreeView::class.java)!!,
            MappingTree::class to data.view(MappingTree::class.java)!!
        )
        for (part in request.parts) {
            for (type in part.viewTypes) {
                if (type == UmlMappingTreeView::class || type == MappingTree::class) continue
                val previous = views.put(type, part.view(type.java)!!)
                if (previous != null) {
                    ctx.logger.info(
                        "[Unimined/Mappings] view ${type.simpleName} supplied by " +
                            "multiple sources in '${ctx.environmentId}'; using the " +
                            "last declaration"
                    )
                }
            }
        }
        return ViewMapMappingData(views)
    }

}
