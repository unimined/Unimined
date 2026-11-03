package xyz.wagyourtail.unimined.transform.internal.classtransform

import net.lenni0451.classtransform.annotations.CSlice
import net.lenni0451.classtransform.annotations.CTarget
import net.lenni0451.classtransform.targets.IInjectionTarget
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.AbstractInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode

/**
 * Injection target matching `super.<sameName>` invocations —
 * `INVOKESPECIAL` instructions whose method name equals the enclosing
 * method's name (i.e. the overridden super call). Used when the super
 * member's name is only known at apply time (obf/srg), so a fixed
 * `Lowner;name(desc)ret` INVOKE target can't be written.
 *
 * [CTarget.target] narrows to the called method's descriptor
 * (e.g. `"(Ljava/lang/String;)Z"`); empty matches all same-named
 * super calls. Register via
 * `TransformerManager.addInjectionTarget("SUPER_SELF", SelfSuperTarget())`.
 */
class SelfSuperTarget : IInjectionTarget {

    override fun getTargets(
        injectionTargets: MutableMap<String, IInjectionTarget>,
        method: MethodNode, target: CTarget, slice: CSlice?
    ): List<AbstractInsnNode> {
        val targets = mutableListOf<AbstractInsnNode>()
        var i = 0
        for (insn in getSlice(injectionTargets, method, slice)) {
            if (insn !is MethodInsnNode) continue
            if (insn.opcode != Opcodes.INVOKESPECIAL
                || insn.name != method.name
                || insn.name == "<init>"
                || (target.target.isNotEmpty() && insn.desc != target.target)
            ) continue
            if (target.ordinal == -1 || target.ordinal == i) targets.add(insn)
            i++
        }
        return targets
    }
}
