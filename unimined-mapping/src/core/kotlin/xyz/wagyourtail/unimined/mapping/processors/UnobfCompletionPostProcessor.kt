package xyz.wagyourtail.unimined.mapping.processors

import xyz.wagyourtail.unimined.api.mapping.MappingContext
import xyz.wagyourtail.unimined.api.mapping.MappingData
import xyz.wagyourtail.unimined.api.mapping.MappingPostProcessor
import xyz.wagyourtail.unimined.api.mapping.view
import xyz.wagyourtail.unimined.mapping.*
import xyz.wagyourtail.unimined.mapping.element.mapping.*
import xyz.wagyourtail.unimined.mapping.jvms.ext.FieldNameAndDescriptor
import xyz.wagyourtail.unimined.mapping.jvms.ext.MethodNameAndDescriptor

/**
 * Builtin [MappingPostProcessor] completing missing cells on rows whose
 * present names are all equal — the row describes an un-obfuscated
 * member, so the missing cells carry the same name (loom's
 * `MappingNsCompleter` "un-changed" rule: e.g. a member present as
 * `intermediary == named` gets its missing `official` cell). Rows whose
 * present names differ are real mappings and are left alone — the
 * missing cell can't be guessed.
 *
 * Member descs are remapped through the tree's namespace remapper rather
 * than copied — descriptors are namespace-relative even when the member
 * name agrees across namespaces. No-ops on data without a mutable UML
 * tree.
 */
class UnobfCompletionPostProcessor : MappingPostProcessor {

    override val name = "unobf-completion"

    override fun process(data: MappingData, ctx: MappingContext): MappingData {
        val tree = data.view<MappingTree>()
        (tree as? MutableMappingTree)?.completeUnchangedCells()
        return data
    }

}

private fun MutableMappingTree.completeUnchangedCells() {
    val allNs = namespaces.toList()
    if (allNs.size < 2) return
    val remappers = mutableMapOf<Pair<Namespace, Namespace>, NamespaceRemapper>()
    fun descRemapper(from: Namespace, to: Namespace): NamespaceRemapper =
        remappers.getOrPut(from to to) { remapper(from, to) }

    /** name-only rows: fill missing cells iff all present names equal. */
    fun <T> completeNames(names: Map<Namespace, T>): Map<Namespace, T> {
        if (names.isEmpty() || names.size == allNs.size) return names
        val present = names.values.toSet()
        if (present.size != 1) return names
        val out = names.toMutableMap()
        for (ns in allNs) {
            if (ns !in out) out[ns] = present.single()
        }
        return out
    }

    for (pkg in packages) {
        (pkg as? MutablePackageMapping)?.setNames(completeNames(pkg.names))
    }
    for (cls in classes) {
        if (cls !is MutableClassMapping) continue
        cls.setNames(completeNames(cls.names))
        for (field in cls.fields) {
            if (field !is MutableFieldMapping) continue
            val entries = field.entries
            if (entries.isEmpty() || entries.size == allNs.size) continue
            val present = entries.values.mapTo(mutableSetOf()) { it.name }
            if (present.size != 1) continue
            val name = present.single()
            val donor = entries.entries.first()
            val out = entries.toMutableMap()
            for (ns in allNs) {
                if (ns in out) continue
                val desc = donor.value.descriptor
                    ?.let { descRemapper(donor.key, ns).map(it) }
                out[ns] = FieldNameAndDescriptor.unchecked(
                    if (desc != null) "${name.value};${desc.value}" else name.value
                )
            }
            field.setEntries(out)
        }
        for (method in cls.methods) {
            if (method !is MutableMethodMapping) continue
            val entries = method.entries
            if (entries.isEmpty() || entries.size == allNs.size) continue
            val present = entries.values.mapTo(mutableSetOf()) { it.name }
            if (present.size != 1) continue
            val name = present.single()
            val donor = entries.entries.first()
            val out = entries.toMutableMap()
            for (ns in allNs) {
                if (ns in out) continue
                val desc = donor.value.descriptor
                    ?.let { descRemapper(donor.key, ns).map(it) }
                out[ns] = MethodNameAndDescriptor.unchecked(
                    if (desc != null) "${name.value};${desc.value}" else name.value
                )
            }
            method.setEntries(out)
            for (param in method.parameters) {
                (param as? MutableParameterMapping)
                    ?.setNames(completeNames(param.names))
            }
            for (local in method.localVariables) {
                (local as? MutableLocalVariableMapping)
                    ?.setNames(completeNames(local.names))
            }
        }
        for (wildcard in cls.wildcards) {
            if (wildcard !is MutableWildcardMapping) continue
            for (param in wildcard.parameters) {
                (param as? MutableParameterMapping)
                    ?.setNames(completeNames(param.names))
            }
            for (local in wildcard.localVariables) {
                (local as? MutableLocalVariableMapping)
                    ?.setNames(completeNames(local.names))
            }
        }
    }
}
