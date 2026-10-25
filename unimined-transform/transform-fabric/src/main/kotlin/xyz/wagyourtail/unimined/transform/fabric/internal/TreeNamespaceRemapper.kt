package xyz.wagyourtail.unimined.transform.fabric.internal

import xyz.wagyourtail.unimined.api.mapping.MappingTree
import xyz.wagyourtail.unimined.api.mapping.MappingVisitor

/**
 * Maps names between namespaces through the portable [MappingTree] view.
 * Used to rebase Access Widener directives written in one namespace onto
 * the pipeline's target namespace.
 */
internal class TreeNamespaceRemapper(tree: MappingTree, source: String, target: String) {

    private data class Member(val owner: String, val name: String, val desc: String?)

    private val classes = HashMap<String, String>()
    private val fields = HashMap<Member, String>()
    private val methods = HashMap<Member, String>()
    private val packages = HashMap<String, String>()

    init {
        tree.accept(source, target, object : MappingVisitor {
            override fun visitClass(srcName: String, dstName: String) {
                classes[srcName] = dstName
            }

            override fun visitField(srcClass: String, srcName: String, srcDesc: String?, dstName: String) {
                fields[Member(srcClass, srcName, srcDesc)] = dstName
            }

            override fun visitMethod(srcClass: String, srcName: String, srcDesc: String?, dstName: String) {
                methods[Member(srcClass, srcName, srcDesc)] = dstName
            }

            override fun visitPackage(srcName: String, dstName: String) {
                packages[srcName] = dstName
            }
        })
    }

    fun map(internalName: String): String = classes[internalName]
        ?: packages.entries
            .filter { internalName.startsWith("${it.key}/") }
            .maxByOrNull { it.key.length }
            ?.let { it.value + internalName.substring(it.key.length) }
        ?: internalName

    fun mapField(owner: String, name: String, desc: String?): String =
        fields[Member(owner, name, desc)]
            ?: fields.entries.firstOrNull { it.key.owner == owner && it.key.name == name }?.value
            ?: name

    fun mapMethod(owner: String, name: String, desc: String?): String =
        methods[Member(owner, name, desc)]
            ?: methods.entries.firstOrNull { it.key.owner == owner && it.key.name == name }?.value
            ?: name

    fun mapDesc(desc: String): String =
        Regex("L([^;]+);").replace(desc) { "L${map(it.groupValues[1])};" }

}
