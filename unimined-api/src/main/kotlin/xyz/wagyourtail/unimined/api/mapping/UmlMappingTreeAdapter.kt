package xyz.wagyourtail.unimined.api.mapping

import xyz.wagyourtail.unimined.mapping.Namespace
import xyz.wagyourtail.unimined.mapping.jvms.ext.FieldNameAndDescriptor
import xyz.wagyourtail.unimined.mapping.jvms.four.three.three.MethodDescriptor

/**
 * Adapts a UML [xyz.wagyourtail.unimined.mapping.MappingTree] to the
 * portable api-level [MappingTree] visitor interchange — engines that
 * don't consume UML internals (tiny, source) see the same resolved data.
 *
 * Method-arg/var rows are emitted only when UML models them — the
 * interchange silently drops local names for trees that don't carry them.
 */
class UmlMappingTreeAdapter(
    val uml: xyz.wagyourtail.unimined.mapping.MappingTree
) : MappingTree {

    override val namespaces: List<String>
        get() = uml.namespaces.map { it.name }

    override fun accept(sourceNamespace: String, targetNamespace: String, visitor: MappingVisitor) {
        val src = uml.namespaces.first { it.name == sourceNamespace }
        val dst = uml.namespaces.first { it.name == targetNamespace }

        for (pkg in uml.packages) {
            val s = pkg.names[src]?.value
            val d = pkg.names[dst]?.value
            if (s != null && d != null) visitor.visitPackage(s, d)
        }
        for (cls in uml.classes) {
            val s = cls.names[src]?.value ?: continue
            val d = cls.names[dst]?.value ?: continue
            visitor.visitClass(s, d)
            for (f in cls.fields) {
                val fs = f.names[src]?.value ?: continue
                val fd = f.names[dst]?.value ?: continue
                visitor.visitField(s, fs, srcFieldDesc(src, f.entries), fd)
            }
            for (m in cls.methods) {
                val ms = m.names[src]?.value ?: continue
                val md = m.names[dst]?.value ?: continue
                visitor.visitMethod(s, ms, srcMethodDesc(src, m.descs), md)
            }
        }
    }

    /** The member's descriptor in [src] terms — remapped through the tree
     *  when the source namespace doesn't declare one (e.g. yarn only
     *  carries intermediary descriptors). */
    private fun srcMethodDesc(
        src: Namespace,
        descs: Map<Namespace, MethodDescriptor>
    ): String? {
        descs[src]?.let { return it.value }
        val (ns, desc) = descs.entries.firstOrNull() ?: return null
        return uml.map(ns, src, desc).value
    }

    private fun srcFieldDesc(
        src: Namespace,
        entries: Map<Namespace, FieldNameAndDescriptor>
    ): String? {
        entries[src]?.descriptor?.let { return it.toString() }
        for ((ns, entry) in entries) {
            val desc = entry.descriptor ?: continue
            return uml.map(ns, src, desc).toString()
        }
        return null
    }

}
