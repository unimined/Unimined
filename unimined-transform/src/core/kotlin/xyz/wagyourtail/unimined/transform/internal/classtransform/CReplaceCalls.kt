package xyz.wagyourtail.unimined.transform.internal.classtransform

import net.lenni0451.classtransform.TransformerManager
import net.lenni0451.classtransform.annotations.CSlice
import net.lenni0451.classtransform.annotations.CTarget
import net.lenni0451.classtransform.transformer.AnnotationHandler
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.InsnNode
import org.objectweb.asm.tree.FieldInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.VarInsnNode
import java.util.Locale

/**
 * Replace-calls: copy the annotated method into the target class and
 * rewrite every invocation matched by [target] in [method] into a
 * static call to it. Unlike CRedirect, the target method is looked up
 * at transform-apply time — it may be one synthesized earlier in the
 * same pass (e.g. by [CExtractRange]).
 *
 * The tm must be static and self-contained — it is copied verbatim
 * as a callable method on the target.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class CReplaceCalls(
    val method: String,
    val target: CTarget,
    val optional: Boolean = false,
    /**
     * scopes matches to a region of the method — lets a rewrite hit
     * only e.g. the getName inside the listFiles loop, not earlier
     * zip-check calls with the same signature
     */
    val slice: CSlice = CSlice(),
)

class CReplaceCallsAnnotationHandler : AnnotationHandler() {

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
            val ann = getAnnotation(CReplaceCalls::class.java, tm, transformerManager)
                ?: continue
            val m = transformedClass.methods.firstOrNull { it.name == ann.method }
                ?: continue
            apply(transformerManager, transformedClass, transformer, m, tm, ann)
        }
    }

    private fun apply(
        tm: TransformerManager, cn: ClassNode, transformer: ClassNode,
        m: MethodNode, stub: MethodNode, a: CReplaceCalls
    ) {
        val targets = tm.injectionTargets
        val t = targets[a.target.value.uppercase(Locale.ROOT)]
            ?: error("CReplaceCalls: unknown target '${a.target.value}'")
        val slice = if (a.slice.from.value.isEmpty()) null else a.slice
        val all = m.instructions.toArray().toList()
        val matched = if (slice != null && slice.to.value.isEmpty()) {
            val ft = targets[slice.from.value.uppercase(Locale.ROOT)]
                ?: error("CReplaceCalls: unknown slice target")
            val anchor = ft.getTargets(targets, m, slice.from, null)
                ?.firstOrNull() ?: return
            val ai = all.indexOf(anchor)
            t.getTargets(targets, m, a.target, null)
                ?.filterIsInstance<MethodInsnNode>()
                ?.filter { all.indexOf(it) > ai }
        } else t.getTargets(targets, m, a.target, slice)
            ?.filterIsInstance<MethodInsnNode>()
        if (matched.isNullOrEmpty()) return
        // @CLocalVariable params on the stub: emit a load of the
        // target method's local for each — appended after the call's
        // existing args, so they must be trailing params
        val lvSlots = mutableListOf<Int>()
        val panns = (stub.visibleParameterAnnotations?.toList() ?: emptyList()) +
            (stub.invisibleParameterAnnotations?.toList() ?: emptyList())
        for (plist in panns) plist?.forEach { a ->
            if (a.desc != "Lnet/lenni0451/classtransform/annotations/CLocalVariable;") return@forEach
            a.values?.let { for (i in it.indices step 2)
                if (it[i] == "index") lvSlots += it[i + 1] as Int }
        }
        val argTypes = Type.getArgumentTypes(m.desc)
        val isStatic = m.access and Opcodes.ACC_STATIC != 0
        if (cn.methods.any { it.name == stub.name && it.desc == stub.desc })
            return // idempotent: already applied by an earlier pass
        // copy the helper verbatim — self-contained, no slot remap;
        // transformer-internal refs rewrite to the target class so a
        // helper can call real/synthesized members
        for (insn in stub.instructions) when (insn) {
            is MethodInsnNode -> if (insn.owner == transformer.name) insn.owner = cn.name
            is FieldInsnNode -> if (insn.owner == transformer.name) insn.owner = cn.name
        }
        cn.methods.add(stub)
        for (call in matched) {
            for (slot in lvSlots) m.instructions.insertBefore(call, VarInsnNode(
                loadOpcode(m, argTypes, isStatic, slot), slot))
            call.opcode = Opcodes.INVOKESTATIC
            call.owner = cn.name
            call.name = stub.name
            call.desc = stub.desc
            call.itf = false
        }
    }

    /** load opcode for a local slot — exact for method params,
        ALOAD fallback otherwise */
    private fun loadOpcode(m: MethodNode, argTypes: Array<Type>, isStatic: Boolean, slot: Int): Int {
        var idx = if (isStatic) 0 else 1
        for (at in argTypes) {
            if (idx == slot) return at.getOpcode(Opcodes.ILOAD)
            idx += at.size
        }
        return Opcodes.ALOAD
    }
}
