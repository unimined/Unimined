package xyz.wagyourtail.unimined.api.mapping

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
                visitor.visitField(s, fs, f.entries[src]?.descriptor?.toString(), fd)
            }
            for (m in cls.methods) {
                val ms = m.names[src]?.value ?: continue
                val md = m.names[dst]?.value ?: continue
                visitor.visitMethod(s, ms, m.descs[src]?.value, md)
            }
        }
    }

}
