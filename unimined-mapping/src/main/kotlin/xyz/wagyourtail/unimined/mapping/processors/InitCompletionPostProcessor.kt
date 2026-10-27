package xyz.wagyourtail.unimined.mapping.processors

import xyz.wagyourtail.unimined.api.mapping.MappingContext
import xyz.wagyourtail.unimined.api.mapping.MappingData
import xyz.wagyourtail.unimined.api.mapping.MappingPostProcessor
import xyz.wagyourtail.unimined.api.mapping.UmlMappingTreeView
import xyz.wagyourtail.unimined.api.mapping.view
import xyz.wagyourtail.unimined.mapping.MutableMappingTree
import xyz.wagyourtail.unimined.mapping.tree.impl.fillInitNames

/**
 * Builtin [MappingPostProcessor] completing `<init>` cells: where a
 * constructor row exists in some namespaces but not others, the missing
 * cells are filled with `<init>` plus a desc remapped through the
 * class's namespace remapper — matching Loom's layered-mapping
 * constructor handling. No-ops on data without a mutable UML tree.
 */
class InitCompletionPostProcessor : MappingPostProcessor {

    override val name = "init-completion"

    override fun process(data: MappingData, ctx: MappingContext): MappingData {
        val tree = data.view<UmlMappingTreeView>()?.tree
        (tree as? MutableMappingTree)?.fillInitNames()
        return data
    }

}
