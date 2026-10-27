package xyz.wagyourtail.unimined.mapping.adapters

import kotlin.reflect.KClass
import xyz.wagyourtail.unimined.api.mapping.MappingData
import xyz.wagyourtail.unimined.api.mapping.MappingDataView
import xyz.wagyourtail.unimined.api.mapping.MappingTree
import xyz.wagyourtail.unimined.api.mapping.MappingViewAdapter
import xyz.wagyourtail.unimined.api.mapping.MappingVisitor
import xyz.wagyourtail.unimined.api.mapping.UmlMappingTreeView
import xyz.wagyourtail.unimined.api.mapping.view
import xyz.wagyourtail.unimined.mapping.MutableMappingTree
import xyz.wagyourtail.unimined.mapping.Namespace
import xyz.wagyourtail.unimined.mapping.element.mapping.MutableClassMapping
import xyz.wagyourtail.unimined.mapping.element.mapping.MutableMethodMapping
import xyz.wagyourtail.unimined.mapping.jvms.ext.FieldNameAndDescriptor
import xyz.wagyourtail.unimined.mapping.jvms.ext.MethodNameAndDescriptor
import xyz.wagyourtail.unimined.mapping.jvms.four.two.one.InternalName
import xyz.wagyourtail.unimined.mapping.jvms.four.two.one.PackageName
import xyz.wagyourtail.unimined.mapping.jvms.four.two.two.UnqualifiedName

/**
 * Reconstructs a minimal [UmlMappingTreeView] from portable [MappingTree]
 * rows — the bundled adapter, letting portable-only sources (or future
 * mapping-io views) feed UML-based steps when no richer metadata is
 * needed.
 *
 * Each `accept(base, target)` pass contributes one namespace column; rows
 * merge onto the same tree element via the shared base-namespace name,
 * mirroring how UML's own readers compose partial files. Method arg/var
 * rows carry only the target name (the portable interchange has no
 * source-side param names).
 */
class PortableToUmlTreeAdapter : MappingViewAdapter {

    override val produces: KClass<out MappingDataView> = UmlMappingTreeView::class

    override val consumes: Set<KClass<out MappingDataView>> = setOf(MappingTree::class)

    override fun adapt(data: MappingData): MappingDataView? {
        val portable = data.view<MappingTree>() ?: return null
        val names = portable.namespaces
        if (names.size < 2) return null
        val nss = names.map { Namespace(it) }
        val tree = MutableMappingTree(nss)
        for (i in 1 until names.size) {
            portable.accept(names[0], names[i], UmlVisitor(tree, nss[0], nss[i]))
        }
        return object : UmlMappingTreeView {
            override val tree = tree
        }
    }

    private class UmlVisitor(
        private val tree: MutableMappingTree,
        private val base: Namespace,
        private val target: Namespace
    ) : MappingVisitor {

        private val classes = HashMap<String, MutableClassMapping>()

        override fun visitClass(srcName: String, dstName: String) {
            classes[srcName] = tree.clazz(mapOf(
                base to InternalName.unchecked(srcName),
                target to InternalName.unchecked(dstName)
            ))
        }

        override fun visitPackage(srcName: String, dstName: String) {
            tree.package_(mapOf(
                base to PackageName.unchecked(pkg(srcName)),
                target to PackageName.unchecked(pkg(dstName))
            ))
        }

        private fun method(srcClass: String, srcName: String, srcDesc: String?, dstName: String?): MutableMethodMapping? {
            val cls = classes[srcClass] ?: return null
            val entries = mutableMapOf(base to MethodNameAndDescriptor.unchecked(
                srcDesc?.let { "$srcName;$it" } ?: srcName
            ))
            if (dstName != null) entries[target] = MethodNameAndDescriptor.unchecked(dstName)
            return cls.method(entries)
        }

        override fun visitMethod(srcClass: String, srcName: String, srcDesc: String?, dstName: String) {
            method(srcClass, srcName, srcDesc, dstName)
        }

        override fun visitMethodArg(srcClass: String, srcMethod: String, srcDesc: String?, index: Int, dstName: String) {
            // base-only lookup resolves the row the visitMethod callback created
            method(srcClass, srcMethod, srcDesc, null)
                ?.parameter(index, null, mapOf(target to UnqualifiedName.unchecked(dstName)))
        }

        override fun visitMethodVar(srcClass: String, srcMethod: String, srcDesc: String?, lvIndex: Int, dstName: String) {
            method(srcClass, srcMethod, srcDesc, null)
                ?.localVariable(lvIndex, null, mapOf(target to UnqualifiedName.unchecked(dstName)))
        }

        override fun visitField(srcClass: String, srcName: String, srcDesc: String?, dstName: String) {
            val cls = classes[srcClass] ?: return
            cls.field(mapOf(
                base to FieldNameAndDescriptor.unchecked(srcDesc?.let { "$srcName;$it" } ?: srcName),
                target to FieldNameAndDescriptor.unchecked(dstName)
            ))
        }

        private fun pkg(value: String) = if (value.endsWith('/')) value else "$value/"

    }

}
