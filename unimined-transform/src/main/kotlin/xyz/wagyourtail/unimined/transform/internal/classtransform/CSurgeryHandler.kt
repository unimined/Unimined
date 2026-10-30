package xyz.wagyourtail.unimined.transform.internal.classtransform

import net.lenni0451.classtransform.TransformerManager
import net.lenni0451.classtransform.transformer.AnnotationHandler
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodNode
import java.util.IdentityHashMap

/** stubs stripped at PRE, applied at POST — extraction runs after
    classtransform's own handlers so <clinit>-bound hooks (CRedirect
    etc.) rewrite calls while the tail still lives there, and the
    rewritten instructions ride along into the synthesized method */
private val surgeryStubs = IdentityHashMap<ClassNode, MutableList<MethodNode>>()

private fun isSurgeryStub(m: MethodNode): Boolean {
    val anns = (m.visibleAnnotations ?: emptyList()) + (m.invisibleAnnotations ?: emptyList())
    return anns.any {
        it.desc.endsWith("CExtractRange;") || it.desc.endsWith("CReplaceRange;")
            || it.desc.endsWith("CReplaceCalls;")
    }
}

/** PRE: remove surgery stubs so classtransform doesn't copy them as
    plain members, stash for the POST pass */
class CSurgeryStubStripHandler : AnnotationHandler() {
    override fun transform(
        transformerManager: TransformerManager,
        transformedClass: ClassNode,
        transformer: ClassNode
    ) {
        val iter = transformer.methods.iterator()
        while (iter.hasNext()) {
            val m = iter.next()
            if (isSurgeryStub(m)) {
                iter.remove()
                surgeryStubs.getOrPut(transformer) { mutableListOf() }.add(m)
            }
        }
    }
}

/** POST: extraction, replacement, call rewriting — fixed order */
class CSurgeryAnnotationHandler : AnnotationHandler() {

    private val extract = CExtractRangeAnnotationHandler()
    private val replace = CReplaceRangeAnnotationHandler()
    private val calls = CReplaceCallsAnnotationHandler()

    override fun transform(
        transformerManager: TransformerManager,
        transformedClass: ClassNode,
        transformer: ClassNode
    ) {
        val stubs = surgeryStubs.remove(transformer) ?: return
        extract.transform(transformerManager, transformedClass, transformer, stubs)
        replace.transform(transformerManager, transformedClass, transformer, stubs)
        calls.transform(transformerManager, transformedClass, transformer, stubs)
    }
}
