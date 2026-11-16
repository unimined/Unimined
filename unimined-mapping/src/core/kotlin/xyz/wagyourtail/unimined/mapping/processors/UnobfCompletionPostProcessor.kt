package xyz.wagyourtail.unimined.mapping.processors

import xyz.wagyourtail.unimined.api.mapping.MappingContext
import xyz.wagyourtail.unimined.api.mapping.MappingData
import xyz.wagyourtail.unimined.api.mapping.MappingPostProcessor
import xyz.wagyourtail.unimined.api.mapping.view
import xyz.wagyourtail.unimined.mapping.MappingTree
import xyz.wagyourtail.unimined.mapping.MutableMappingTree
import xyz.wagyourtail.unimined.mapping.tree.impl.fillUnchangedCells

/**
 * Builtin [MappingPostProcessor] completing missing cells on rows whose
 * present names are all equal — the row describes an un-obfuscated
 * member, so the missing cells carry the same name (loom's
 * `MappingNsCompleter` "un-changed" rule: e.g. a member present as
 * `intermediary == named` gets its missing `official` cell). Rows whose
 * present names differ are real mappings and are left alone — the
 * missing cell can't be guessed.
 *
 * Delegates to UML's [fillUnchangedCells]: member descs are remapped
 * through the tree's namespace remapper rather than copied —
 * descriptors are namespace-relative even when the member name agrees
 * across namespaces — and environment-qualified columns
 * (`official:client`-style) are never filled since an empty env cell
 * means "absent on that side". No-ops on data without a mutable UML
 * tree.
 */
class UnobfCompletionPostProcessor : MappingPostProcessor {

    override val name = "unobf-completion"

    override fun process(data: MappingData, ctx: MappingContext): MappingData {
        (data.view<MappingTree>() as? MutableMappingTree)?.fillUnchangedCells()
        return data
    }

}
