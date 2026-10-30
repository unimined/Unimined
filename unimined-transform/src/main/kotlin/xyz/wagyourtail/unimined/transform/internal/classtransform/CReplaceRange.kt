package xyz.wagyourtail.unimined.transform.internal.classtransform

import net.lenni0451.classtransform.TransformerManager
import net.lenni0451.classtransform.annotations.CSlice
import net.lenni0451.classtransform.transformer.AnnotationHandler
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodNode

/**
 * Replace-range surgery: the insns matched by [replace] (a [CSlice]
 * resolved via IInjectionTarget.getSlice) are deleted and the
 * annotated method's *body* is inlined in their place — this stub is
 * real code, not just a declaration.
 *
 * LOCALS: stub params annotated `@CLocalVariable(index = n)` bind to
 * the target method's local slot n (by param position); all other
 * stub locals are remapped to free slots above the target's. calls
 * to other members of the transformer class are rewritten to the
 * target class — a stub can call a method synthesized by
 * [CExtractRange] without @CShadow parse-time binding.
 *
 * VALIDATION (throws): no jump/switch/try-catch may cross the
 * replaced boundary.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class CReplaceRange(
    val method: String,
    val replace: CSlice,
)

class CReplaceRangeAnnotationHandler : AnnotationHandler() {

    override fun transform(
        transformerManager: TransformerManager,
        transformedClass: ClassNode,
        transformer: ClassNode
    ) = Unit // driven by CSurgeryAnnotationHandler via stubs

    fun transform(
        transformerManager: TransformerManager,
        transformedClass: ClassNode,
        transformer: ClassNode,
        stubs: List<MethodNode>
    ) {
        val iter = stubs.iterator()
        while (iter.hasNext()) {
            val tm = iter.next()
            val ann = getAnnotation(CReplaceRange::class.java, tm, transformerManager)
                ?: continue
            val m = transformedClass.methods.firstOrNull { it.name == ann.method }
                ?: continue
            apply(transformerManager, transformedClass, transformer, m, tm, ann)
        }
    }

    private fun apply(
        tm: TransformerManager, cn: ClassNode, transformer: ClassNode,
        m: MethodNode, stub: MethodNode, a: CReplaceRange
    ) {
        val (si, ei) = RangeSurgery.sliceBounds(tm.injectionTargets, m, a.replace)
            ?: return
        RangeSurgery.validateBoundary(cn, m, "CReplaceRange", si, ei)
        val lp = RangeSurgery.parseLocalParams(stub)
        val list = m.instructions.toArray().toList()
        val body = RangeSurgery.inlineBody(
            stub, lp.slotMap, RangeSurgery.freeLocalBase(m), transformer, cn)
        for (i in ei downTo si) m.instructions.remove(list[i])
        val anchor = m.instructions.toArray().getOrNull(si)
        for (x in body.toArray()) {
            if (anchor != null) m.instructions.insertBefore(anchor, x)
            else m.instructions.add(x)
        }
    }
}
