package xyz.wagyourtail.unimined.transform.internal.classtransform

import net.lenni0451.classtransform.TransformerManager
import net.lenni0451.classtransform.exceptions.SliceException
import net.lenni0451.classtransform.annotations.CSlice
import net.lenni0451.classtransform.targets.IInjectionTarget
import org.objectweb.asm.tree.*
import org.objectweb.asm.tree.analysis.Analyzer
import org.objectweb.asm.tree.analysis.BasicInterpreter
import java.util.Locale

/** shared helpers for the C*Range surgery handlers */
internal object RangeSurgery {

    /** resolve a [CSlice] to insn indexes (from..to) in [m], or null */
    fun sliceBounds(
        targets: Map<String, IInjectionTarget>, m: MethodNode, slice: CSlice
    ): Pair<Int, Int>? {
        val fromT = targets[slice.from.value.uppercase(Locale.ROOT)]
            ?: error("unknown slice target '${slice.from.value}'")
        val insns = try {
            if (slice.to.value.isEmpty()) {
                val from = fromT.getTargets(targets, m, slice.from, null)
                    ?: return null
                val fi = m.instructions.toArray().toList().indexOf(from.first())
                m.instructions.toArray().toList().drop(fi)
            } else fromT.getSlice(targets, m, slice)
        } catch (e: SliceException) {
            // already-applied or ambiguous slice — treated as no-op
            // (idempotent across multiple transform passes)
            return null
        } ?: return null
        val list = m.instructions.toArray().toList()
        val si = list.indexOf(insns.first()); val ei = list.indexOf(insns.last())
        return if (si >= 0 && ei >= 0) si to ei else null
    }

    fun frames(cn: ClassNode, m: MethodNode) =
        Analyzer(BasicInterpreter()).analyze(cn.name, m)

    /** reject jumps/switches/try-catch crossing [si..ei] */
    fun validateBoundary(cn: ClassNode, m: MethodNode, tag: String, si: Int, ei: Int) {
        val list = m.instructions.toArray().toList()
        for ((i, insn) in list.withIndex()) {
            val inside = i in si..ei
            when (insn) {
                is JumpInsnNode -> {
                    val ti = list.indexOf(insn.label)
                    if (inside != (ti in si..ei))
                        error("$tag: jump crosses boundary in ${cn.name}.${m.name}")
                }
                is LookupSwitchInsnNode -> for (l in insn.labels + insn.dflt)
                    if (inside != (list.indexOf(l) in si..ei))
                        error("$tag: switch crosses boundary in ${cn.name}.${m.name}")
                is TableSwitchInsnNode -> for (l in insn.labels + insn.dflt)
                    if (inside != (list.indexOf(l) in si..ei))
                        error("$tag: switch crosses boundary in ${cn.name}.${m.name}")
            }
        }
        m.tryCatchBlocks?.forEach {
            val s = list.indexOf(it.start); val e = list.indexOf(it.end)
            val h = list.indexOf(it.handler)
            val inside = s in si..ei || e in si..ei || h in si..ei
            val all = s in si..ei && e in si..ei && h in si..ei
            if (inside && !all)
                error("$tag: try/catch crosses boundary in ${cn.name}.${m.name}")
        }
    }

    /** @CLocalVariable param mapping: target slot -> stub param index,
        plus the set of slots marked modifiable */
    data class LocalParams(val slotMap: Map<Int, Int>, val modifiable: Set<Int>)

    fun parseLocalParams(stub: MethodNode): LocalParams {
        val all = mutableListOf<AnnotationNode>()
        stub.visibleParameterAnnotations?.forEach { it?.forEach { a -> all.add(a) } }
        stub.invisibleParameterAnnotations?.forEach { it?.forEach { a -> all.add(a) } }
        val slotMap = mutableMapOf<Int, Int>()
        val modifiable = mutableSetOf<Int>()
        var pi = 0
        for (a in all) {
            if (a.desc != "Lnet/lenni0451/classtransform/annotations/CLocalVariable;") continue
            var idx = -1; var mod = false
            a.values?.let {
                for (i in it.indices step 2) {
                    if (it[i] == "index") idx = it[i + 1] as Int
                    if (it[i] == "modifiable") mod = it[i + 1] as Boolean
                }
            }
            if (idx >= 0) { slotMap[idx] = pi++; if (mod) modifiable += idx }
        }
        return LocalParams(slotMap, modifiable)
    }

    /** clone a method's insns, mapping stub param slots -> target
        local slots via [slotMap] (target slot -> param index), other
        stub locals -> [freeBase] + i, and transformer-internal calls
        -> target-internal calls */
    fun inlineBody(
        stub: MethodNode, slotMap: Map<Int, Int>, freeBase: Int,
        transformer: ClassNode, target: ClassNode
    ): InsnList {
        val p2s = slotMap.entries.associate { it.value to it.key }
        val labels = mutableMapOf<LabelNode, LabelNode>()
        for (insn in stub.instructions) if (insn is LabelNode) labels[insn] = LabelNode()
        val out = InsnList()
        for (insn in stub.instructions) {
            val c = insn.clone(labels)
            when (c) {
                is VarInsnNode -> c.`var` = p2s[c.`var`] ?: (freeBase + c.`var`)
                is IincInsnNode -> c.`var` = p2s[c.`var`] ?: (freeBase + c.`var`)
                is MethodInsnNode -> if (c.owner == transformer.name) c.owner = target.name
                is FieldInsnNode -> if (c.owner == transformer.name) c.owner = target.name
            }
            out.add(c)
        }
        return out
    }

    /** first free local slot above everything [m] currently uses
        (+2 padding for wide types) */
    fun freeLocalBase(m: MethodNode): Int {
        var free = 0
        for (insn in m.instructions) when (insn) {
            is VarInsnNode -> free = maxOf(free, insn.`var` + 2)
            is IincInsnNode -> free = maxOf(free, insn.`var` + 1)
        }
        return free
    }
}
