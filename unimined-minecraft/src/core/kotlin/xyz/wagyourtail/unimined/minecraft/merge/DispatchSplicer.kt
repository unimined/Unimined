package xyz.wagyourtail.unimined.minecraft.merge

import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.AnnotationNode
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FrameNode
import org.objectweb.asm.tree.InsnList
import org.objectweb.asm.tree.JumpInsnNode
import org.objectweb.asm.tree.LabelNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode

/**
 * How [DispatchMerger] splices two divergent same-desc method bodies
 * into one merged method behind the runtime side check. A splicer may
 * return `null` to decline — the policy then falls back to
 * [WholeBodySplicer], which always applies.
 */
fun interface DispatchSplicer {

    /** Merge [client]/[server] (same name+desc, divergent bodies)
     *  into one method, or `null` when this strategy can't express
     *  the splice. [classVersion] is the merged class's version —
     *  splicers must emit stack-map frames at new basic-block
     *  boundaries when it is ≥ V1_7 (51). */
    fun splice(
        owner: String, classVersion: Int,
        client: MethodNode, server: MethodNode,
    ): MethodNode?
}

/**
 * Fallback splicer: both bodies emitted as disjoint branches behind
 * one guard — always applicable; see [DispatchMerger]'s class doc
 * for the emitted shape and why it covers `<init>`/`<clinit>`. */
internal class WholeBodySplicer(
    private val sideProperty: String,
) : DispatchSplicer {

    override fun splice(
        owner: String, classVersion: Int,
        client: MethodNode, server: MethodNode,
    ): MethodNode {
        val c = cloneMethod(client)
        val s = cloneMethod(server)

        val clientLabel = LabelNode()
        val insns = InsnList()
        emitSideGuard(insns, sideProperty, clientLabel)
        // fall-through into the server body is a basic-block
        // boundary under split verification — needs the initial
        // frame; the IFEQ target needs the same
        insns.add(initialFrame(owner, s))
        appendBody(s, insns)
        insns.add(clientLabel)
        insns.add(initialFrame(owner, c))
        appendBody(c, insns)
        s.instructions = insns
        mergeMethodMetadata(s, c, mergeTryCatch = true)
        s.maxStack = maxOf(s.maxStack, 2)
        return s
    }
}

/** Clone with header — `accept()` visits only method *contents*;
 *  access/name/desc/signature/exceptions come from the ctor. */
internal fun cloneMethod(m: MethodNode): MethodNode =
    MethodNode(m.access, m.name, m.desc, m.signature,
        m.exceptions?.toTypedArray()).also(m::accept)

/** `ldc prop / getBoolean / IFEQ→client` — truthy picks server. */
internal fun emitSideGuard(
    into: InsnList, sideProperty: String, clientLabel: LabelNode,
) {
    into.add(LdcInsnNode(sideProperty))
    into.add(MethodInsnNode(
        Opcodes.INVOKESTATIC, "java/lang/Boolean",
        "getBoolean", "(Ljava/lang/String;)Z", false))
    into.add(JumpInsnNode(Opcodes.IFEQ, clientLabel))
}

/** Union side metadata into [s] (a clone of the server node whose
 *  instructions were spliced separately): access weakens,
 *  exceptions/LVT/annotations union, maxes widen. [mergeTryCatch]
 *  off for sectioned splicers — they place tcbs per-section. */
internal fun mergeMethodMetadata(
    s: MethodNode, c: MethodNode, mergeTryCatch: Boolean,
) {
    if (mergeTryCatch) {
        s.tryCatchBlocks = (s.tryCatchBlocks.orEmpty() +
                c.tryCatchBlocks.orEmpty()).toMutableList()
    }
    s.localVariables = (s.localVariables.orEmpty() +
            c.localVariables.orEmpty()).toMutableList()
    s.visibleAnnotations = unionAnnotations(
        s.visibleAnnotations, c.visibleAnnotations)
    s.invisibleAnnotations = unionAnnotations(
        s.invisibleAnnotations, c.invisibleAnnotations)
    s.visibleTypeAnnotations = unionAnnotations(
        s.visibleTypeAnnotations, c.visibleTypeAnnotations)
    s.invisibleTypeAnnotations = unionAnnotations(
        s.invisibleTypeAnnotations, c.invisibleTypeAnnotations)
    s.access = ClassMerger.selectWeakerAccess(
        c.access, s.access) and
            Opcodes.ACC_NATIVE.inv() and Opcodes.ACC_ABSTRACT.inv()
    s.exceptions = ((s.exceptions ?: emptyList()) +
            (c.exceptions ?: emptyList())).distinct()
    s.maxLocals = maxOf(s.maxLocals, c.maxLocals)
    s.maxStack = maxOf(s.maxStack, c.maxStack)
}

private fun <T : AnnotationNode> unionAnnotations(
    dst: MutableList<T>?, extra: List<T>?,
): MutableList<T>? {
    if (extra.isNullOrEmpty()) return dst
    val merged = dst ?: mutableListOf()
    for (a in extra) {
        if (merged.none {
                ClassNodeEquality.areAnnotationNodesEqual(it, a) }) {
            merged.add(a)
        }
    }
    return merged
}

/** Move the body's insns into [into]; leading [FrameNode]s
 *  (pre-first-real-insn, i.e. original offset 0) are dropped —
 *  the [initialFrame] emitted at each body head supersedes them,
 *  and two frames at one offset don't serialize. */
internal fun appendBody(from: MethodNode, into: InsnList) {
    var head = true
    for (insn in from.instructions.toArray()) {
        if (head && insn is FrameNode) continue
        if (insn.opcode != -1) head = false
        into.add(insn)
    }
}

/** [Opcodes.F_NEW] declaring the method's initial locals —
 *  `uninitializedThis` for `<init>`, the owner ref for other
 *  instance methods, then the descriptor's params. */
internal fun initialFrame(owner: String, m: MethodNode): FrameNode {
    val locals = mutableListOf<Any>()
    if (m.access and Opcodes.ACC_STATIC == 0) {
        locals += if (m.name == "<init>")
            Opcodes.UNINITIALIZED_THIS else owner
    }
    for (t in Type.getMethodType(m.desc).argumentTypes) {
        locals += frameType(t)
    }
    return FrameNode(Opcodes.F_NEW, locals.size,
        locals.toTypedArray(), 0, null)
}

/** [Type] → stack-map-frame verification-type encoding. */
internal fun frameType(t: Type): Any = when (t.sort) {
    Type.FLOAT -> Opcodes.FLOAT
    Type.LONG -> Opcodes.LONG
    Type.DOUBLE -> Opcodes.DOUBLE
    Type.ARRAY -> t.descriptor
    Type.OBJECT -> t.internalName
    else -> Opcodes.INTEGER
}
