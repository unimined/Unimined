package xyz.wagyourtail.unimined.transform.internal.classtransform

import net.lenni0451.classtransform.TransformerManager
import net.lenni0451.classtransform.annotations.CLocalVariable
import net.lenni0451.classtransform.annotations.CTarget
import org.objectweb.asm.tree.analysis.Analyzer
import org.objectweb.asm.tree.analysis.BasicInterpreter
import net.lenni0451.classtransform.transformer.AnnotationHandler
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.*
import java.util.Locale

/**
 * Extract-range surgery: deletes the insns matched by [remove]
 * (a [CSlice] resolved through IInjectionTarget.getSlice) and moves
 * everything *after* it into a new method on the target class. The
 * annotated method supplies the synthesized method's name, desc and
 * access flags — it is a declaration, never copied as code.
 *
 * LOCALS — how slots cross the method boundary:
 *  - each stub parameter annotated `@CLocalVariable(index = n)`
 *    receives the value of the target method's local slot n at the
 *    cut point (in parameter order). locals the tail writes into
 *    *new* slots become ordinary locals of the synthesized method.
 *  - a local the tail reads but never writes, and that was
 *    initialized before/in the removed range, must be declared as a
 *    `@CLocalVariable` param — it cannot cross otherwise (error).
 *  - writes to a mapped param slot are "live-out": they must be
 *    marked `@CLocalVariable(modifiable = true)`. single modifiable
 *    param -> the stub's declared return type carries it back;
 *    multiple -> the stub must return `Object[]` (param order,
 *    primitives boxed). callers (`@CShadow`, inject interfaces,
 *    inlined code) store the value(s) back — e.g.
 *    `f = readFromClassPath(f)` or `Object[] o = m(...);`.
 *  - writes to a slot initialized before the cut that is *not* a
 *    mapped param are rejected — the write would be silently lost.
 *
 * VALIDATION (throws on violation):
 *  - no jumps/switches/try-catch may cross the cut boundaries
 *  - stack delta across the boundary must be frame_same or
 *    frame_same+1 (via ASM Analyzer)
 *  - void return + modifiable params is an error
 *
 * COMPOSE the replacement call site with ordinary handlers
 * (@CShadow + @CInject/CInline) — extraction runs at
 * HandlerPosition.PRE so later handlers see the synthesized method.
 * Callers that need the synthesized method BEFORE it exists must
 * run in a second transform pass (as ModLoaderDevFixes does).
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class CExtractRange(
    val method: String,
    /** cut point — everything AFTER the matched insn moves into the
        synthesized method (resolved via IInjectionTarget.getTargets) */
    val after: CTarget,
)

private val WRAPPERS = mapOf(
    Type.BOOLEAN to "java/lang/Boolean", Type.CHAR to "java/lang/Character",
    Type.BYTE to "java/lang/Byte", Type.SHORT to "java/lang/Short",
    Type.INT to "java/lang/Integer", Type.FLOAT to "java/lang/Float",
    Type.LONG to "java/lang/Long", Type.DOUBLE to "java/lang/Double",
)

class CExtractRangeAnnotationHandler : AnnotationHandler() {

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
            val ann = getAnnotation(CExtractRange::class.java, tm, transformerManager)
                ?: continue
            // stub declares the extracted method's signature —
            // handled here, never copied verbatim
            // idempotent: a later transform pass on an already-fixed
            // class must not extract again
            if (transformedClass.methods.any { it.name == tm.name && it.desc == tm.desc }) continue
            val method = transformedClass.methods.firstOrNull {
                it.name == ann.method
            } ?: continue // optional: target absent
            apply(transformerManager, transformedClass, method, ann, tm)
        }
    }

    private fun apply(manager: TransformerManager, cn: ClassNode, m: MethodNode, a: CExtractRange, stub: MethodNode) {
        val targets = manager.injectionTargets
        val afterT = targets[a.after.value.uppercase(Locale.ROOT)]
            ?: error("CExtractRange: unknown after target '${a.after.value}'")
        val matched = afterT.getTargets(targets, m, a.after, null)
            ?: error("CExtractRange: after target not found in ${cn.name}.${m.name}")
        if (matched.size != 1)
            error("CExtractRange: after must match exactly one insn in ${cn.name}.${m.name}, got ${matched.size}")
        val list = m.instructions.toArray().toList()
        val endIdx = list.indexOf(matched.first())

        // frame validation: the tail entry is a fresh method — its
        // entry stack must be F_SAME (0) or F_SAME1 (one value the
        // `after` insn produced, handed to the extracted method as
        // an extra arg). locals at tail entry are bridged via
        // @CLocalVariable params
        // maxLocals may undercount what earlier handlers injected
        for (insn in list) when (insn) {
            is VarInsnNode -> m.maxLocals = maxOf(m.maxLocals, insn.`var` + 2)
            is IincInsnNode -> m.maxLocals = maxOf(m.maxLocals, insn.`var` + 1)
        }
        val frames = Analyzer(BasicInterpreter()).analyze(cn.name, m)
        val endF = frames[endIdx + 1]
            ?: error("CExtractRange: unreachable tail entry in ${cn.name}.${m.name}")
        if (endF.stackSize !in 0..1)
            error("CExtractRange: tail entry stack ${endF.stackSize} in ${cn.name}.${m.name} — must be 0 or 1")
        val boundary = endIdx + 1
        val head = list.subList(0, boundary)
        val tail = list.subList(boundary, list.size)

        // boundary validation: no branch/tcb crossing the cut
        fun inRange(l: LabelNode) = tail.any { it === l }
        fun crosses(insn: AbstractInsnNode, inside: Boolean, targets: List<LabelNode>): Boolean =
            inside && targets.any { t -> !inRange(t) } ||
            !inside && targets.any { inRange(it) }
        fun labelTargets(insn: AbstractInsnNode): List<LabelNode> = when (insn) {
            is JumpInsnNode -> listOf(insn.label)
            is TableSwitchInsnNode -> listOf(insn.dflt) + insn.labels
            is LookupSwitchInsnNode -> listOf(insn.dflt) + insn.labels
            else -> emptyList()
        }
        for ((i, insn) in list.withIndex()) {
            val inside = i >= boundary // tail insns are "inside"
            if (crosses(insn, inside, labelTargets(insn)))
                error("CExtractRange: branch crosses extraction boundary in ${cn.name}.${m.name}")
        }
        for (tcb in m.tryCatchBlocks) {
            val t = list.indexOf(tcb.start); val e2 = list.indexOf(tcb.end); val h = list.indexOf(tcb.handler)
            if ((t < boundary) != (e2 < boundary) || (t < boundary) != (h < boundary))
                error("CExtractRange: try-catch crosses extraction boundary in ${cn.name}.${m.name}")
        }

        // stub params annotated @CLocalVariable(index=n) map target
        // local slots -> extracted params, in parameter order
        val slotMap = mutableMapOf<Int, Int>() // slot -> param index
        val stubAnnos = (stub.visibleParameterAnnotations ?: arrayOfNulls(0))
            .toList() + (stub.invisibleParameterAnnotations ?: arrayOfNulls(0)).toList()
        val allP = mutableListOf<AnnotationNode>()
        stub.visibleParameterAnnotations?.forEach { it?.forEach { a -> allP.add(a) } }
        stub.invisibleParameterAnnotations?.forEach { it?.forEach { a -> allP.add(a) } }
        var pi = 0
        val modifiable = mutableSetOf<Int>() // slots
        for (a in allP) {
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

        // build extracted method from tail — signature comes from
        // the annotated stub method's own name/desc, verbatim
        val nm = MethodNode(Opcodes.ACC_STATIC or (stub.access and 7),
            stub.name, stub.desc, null, null)
        val tl = InsnList()
        var labels = mutableMapOf<LabelNode, LabelNode>()
        // keep original label nodes — branches inside tail stay valid
        tail.forEach { tl.add(it) }
        nm.instructions = tl
        // remap param local slots -> arg slots
        // locals read by the tail before being written there must
        // be declared params (@CLocalVariable). writes to a declared
        // param slot = live-out: at most one, returned by the
        // synthesized method and stored back after the call site
        // (same idea as @CLocalVariable(modifiable) write-back).
        // writes to undeclared live-in slots are rejected.
        val written = mutableSetOf<Int>()
        for (insn in nm.instructions.toArray()) {
            if (insn is VarInsnNode) {
                if (insn.opcode in Opcodes.ILOAD..Opcodes.ALOAD &&
                    insn.`var` !in written && insn.`var` !in slotMap)
                    error("CExtractRange: tail reads undeclared local slot ${insn.`var`} in ${cn.name}.${m.name} — annotate a param with @CLocalVariable. tail=${nm.instructions.toArray().joinToString { it.javaClass.simpleName + ':' + it.opcode }}")
                if (insn.opcode in Opcodes.ISTORE..Opcodes.ASTORE) {
                    if (insn.`var` !in slotMap && insn.`var` < endF.locals &&
                        endF.getLocal(insn.`var`) != null)
                        error("CExtractRange: tail writes live-in local slot ${insn.`var`} in ${cn.name}.${m.name} — map it to a param for live-out")
                    if (insn.`var` in slotMap && insn.`var` !in modifiable) {
                        error("CExtractRange: tail writes non-modifiable param slot ${insn.`var`} in ${cn.name}.${m.name} — the write would be lost; set modifiable = true")
                    }
                    written += insn.`var`
                }
            } else if (insn is IincInsnNode) {
                if (insn.`var` !in slotMap && insn.`var` !in written)
                    error("CExtractRange: iinc on undeclared local slot ${insn.`var`} in ${cn.name}.${m.name}")
                if (insn.`var` in slotMap && insn.`var` !in modifiable)
                    error("CExtractRange: iinc on non-modifiable param slot ${insn.`var`} in ${cn.name}.${m.name} — set modifiable = true")
            }
        }
        for (insn in nm.instructions) {
            if (insn is VarInsnNode || insn is IincInsnNode) {
                val slot = if (insn is VarInsnNode) insn.`var` else (insn as IincInsnNode).`var`
                val p = slotMap[slot]
                if (p != null) {
                    if (insn is VarInsnNode) insn.`var` = p else (insn as IincInsnNode).`var` = p
                }
            }
        }

        // live-out via return value: modifiable params are packed
        // into the return. single modifiable -> the stub's declared
        // return type; multiple (or explicit) -> Object[]. call sites
        // (injected code / inject interface / CShadow callers) store
        // values back — the signature is never mutated
        val retType = Type.getReturnType(stub.desc)
        val modParams = slotMap.entries.filter { it.key in modifiable }
            .sortedBy { it.value }.map { it.value }
        if (modifiable.isNotEmpty() && retType == Type.VOID_TYPE)
            error("CExtractRange: modifiable params require a non-void stub return in ${cn.name}.${m.name}")
        val packArray = modParams.size > 1 || retType == Type.getType(Any::class.java)
        for (insn in nm.instructions.toArray()) {
            if (insn.opcode == Opcodes.RETURN) {
                val pack = InsnList()
                if (packArray) {
                    pack.add(IntInsnNode(Opcodes.BIPUSH, modParams.size))
                    pack.add(TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Object"))
                    modParams.forEachIndexed { i, p ->
                        val t = Type.getArgumentTypes(stub.desc)[p]
                        pack.add(InsnNode(Opcodes.DUP))
                        pack.add(IntInsnNode(Opcodes.BIPUSH, i))
                        pack.add(VarInsnNode(t.getOpcode(Opcodes.ILOAD), p))
                        if (t.sort < Type.ARRAY) {
                            val w = WRAPPERS[t.sort] ?: error("no wrapper for $t")
                            pack.add(MethodInsnNode(Opcodes.INVOKESTATIC, w,
                                "valueOf", "(${t.descriptor})L$w;", false))
                        }
                        pack.add(InsnNode(Opcodes.AASTORE))
                    }
                } else if (modParams.size == 1) {
                    pack.add(VarInsnNode(retType.getOpcode(Opcodes.ILOAD), modParams[0]))
                }
                if (pack.size() > 0) {
                    nm.instructions.insertBefore(insn, pack)
                    nm.instructions.set(insn, InsnNode(retType.getOpcode(Opcodes.IRETURN)))
                }
            }
        }
        // move fully-inside tryCatchBlocks
        nm.tryCatchBlocks = m.tryCatchBlocks.filter { tcb ->
            tail.any { it === tcb.start }
        }
        m.tryCatchBlocks.removeAll(nm.tryCatchBlocks)

        // rebuild original method: prefix only; the tail usually
        // carried the RETURN — reterminate void methods so TAIL
        // injections and the verifier still work
        val nl = InsnList()
        head.forEach { nl.add(it) }
        val lastOp = nl.last?.opcode
        if (lastOp == null || lastOp < Opcodes.IRETURN || lastOp > Opcodes.RETURN) {
            if (!m.desc.endsWith(")V"))
                error("CExtractRange: cannot reterminate non-void ${cn.name}.${m.name}")
            nl.add(InsnNode(Opcodes.RETURN))
        }
        m.instructions = nl

        cn.methods.add(nm)
    }
}
