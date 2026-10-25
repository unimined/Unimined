package xyz.wagyourtail.unimined.api.mapping

/**
 * Acceptor for the namespace-directed mapping interchange — the documented
 * shape remap engines consume without depending on the resolving engine
 * (UML). Fed by [MappingTree.accept].
 *
 * All names are JVM internal names/descriptors (`a/b/C`, `()V`). Member
 * callbacks carry the *source* name of their owning class; descriptors are
 * nullable because some source formats (e.g. sources-level mappings) lack
 * them — consumers should treat a `null` descriptor as "applies to every
 * member of that name".
 */
interface MappingVisitor {

    fun visitClass(srcName: String, dstName: String) {}

    fun visitField(srcClass: String, srcName: String, srcDesc: String?, dstName: String) {}

    fun visitMethod(srcClass: String, srcName: String, srcDesc: String?, dstName: String) {}

    fun visitMethodArg(srcClass: String, srcMethod: String, srcDesc: String?, index: Int, dstName: String) {}

    fun visitMethodVar(srcClass: String, srcMethod: String, srcDesc: String?, lvIndex: Int, dstName: String) {}

    fun visitPackage(srcName: String, dstName: String) {}

}
