package xyz.wagyourtail.unimined.minecraft.merge

import org.objectweb.asm.Attribute
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.*

/**
 * Structural equality for ASM tree nodes — the [ClassMerger]/policy
 * callers ask "are these the same member?" without comparing identity.
 * Annotation and attribute lists compare as multisets (remove matched
 * pairs); label-bearing instructions compare targets by list index,
 * not [LabelNode] identity, since the two lists are built separately.
 */
internal object ClassNodeEquality {

    fun areFieldNodesEqual(a: FieldNode, b: FieldNode): Boolean {
        if (a.name != b.name) return false
        if (a.desc != b.desc) return false
        if (a.value != b.value) return false
        // check static part of access
        if (a.access and Opcodes.ACC_STATIC != b.access and Opcodes.ACC_STATIC) return false
        val aVisibleAnnotations = a.visibleAnnotations?.toMutableList() ?: mutableListOf()
        val bVisibleAnnotations = b.visibleAnnotations?.toMutableList() ?: mutableListOf()
        if (aVisibleAnnotations.size != bVisibleAnnotations.size) return false
        aVisibleAnnotations.removeIf {
            bVisibleAnnotations.any { it2 ->
                if (areAnnotationNodesEqual(it, it2)) {
                    bVisibleAnnotations.remove(it2)
                    true
                } else {
                    false
                }
            }
        }
        if (aVisibleAnnotations.isNotEmpty()) return false
        if (bVisibleAnnotations.isNotEmpty()) return false
        val aInvisibleAnnotations = a.invisibleAnnotations?.toMutableList() ?: mutableListOf()
        val bInvisibleAnnotations = b.invisibleAnnotations?.toMutableList() ?: mutableListOf()
        if (aInvisibleAnnotations.size != bInvisibleAnnotations.size) return false
        aInvisibleAnnotations.removeIf {
            bInvisibleAnnotations.any { it2 ->
                if (areAnnotationNodesEqual(it, it2)) {
                    bInvisibleAnnotations.remove(it2)
                    true
                } else {
                    false
                }
            }
        }
        if (aInvisibleAnnotations.isNotEmpty()) return false
        if (bInvisibleAnnotations.isNotEmpty()) return false
        val aVisibleTypeAnnotations = a.visibleTypeAnnotations?.toMutableList() ?: mutableListOf()
        val bVisibleTypeAnnotations = b.visibleTypeAnnotations?.toMutableList() ?: mutableListOf()
        if (aVisibleTypeAnnotations.size != bVisibleTypeAnnotations.size) return false
        aVisibleTypeAnnotations.removeIf {
            bVisibleTypeAnnotations.any { it2 ->
                if (areAnnotationNodesEqual(it, it2)) {
                    bVisibleTypeAnnotations.remove(it2)
                    true
                } else {
                    false
                }
            }
        }
        if (aVisibleTypeAnnotations.isNotEmpty()) return false
        if (bVisibleTypeAnnotations.isNotEmpty()) return false
        val aInvisibleTypeAnnotations = a.invisibleTypeAnnotations?.toMutableList() ?: mutableListOf()
        val bInvisibleTypeAnnotations = b.invisibleTypeAnnotations?.toMutableList() ?: mutableListOf()
        if (aInvisibleTypeAnnotations.size != bInvisibleTypeAnnotations.size) return false
        aInvisibleTypeAnnotations.removeIf {
            bInvisibleTypeAnnotations.any { it2 ->
                if (areAnnotationNodesEqual(it, it2)) {
                    bInvisibleTypeAnnotations.remove(it2)
                    true
                } else {
                    false
                }
            }
        }
        if (aInvisibleTypeAnnotations.isNotEmpty()) return false
        if (bInvisibleTypeAnnotations.isNotEmpty()) return false
        val aAttributes = a.attrs?.toMutableList() ?: mutableListOf()
        val bAttributes = b.attrs?.toMutableList() ?: mutableListOf()
        if (aAttributes.size != bAttributes.size) return false
        aAttributes.removeIf {
            bAttributes.any { it2 ->
                if (areAttributesEqual(it, it2)) {
                    bAttributes.remove(it2)
                    true
                } else {
                    false
                }
            }
        }
        if (aAttributes.isNotEmpty()) return false
        if (bAttributes.isNotEmpty()) return false
        return true
    }

    fun areMethodNodesEqual(a: MethodNode, b: MethodNode): Boolean {
        if (a.name != b.name) return false
        if (a.desc != b.desc) return false
        // check static part of access
        if (a.access and Opcodes.ACC_STATIC != b.access and Opcodes.ACC_STATIC) return false
//            val aParameters = a.parameters?.toMutableList() ?: mutableListOf()
//            val bParameters = b.parameters?.toMutableList() ?: mutableListOf()
//            if (aParameters.size != bParameters.size) return false
//            aParameters.removeIf {
//                bParameters.any { it2 ->
//                    areParamsEqual(it, it2)
//                }
//            }
//            if (aParameters.isNotEmpty()) return false
        val aVisibleAnnotations = a.visibleAnnotations?.toMutableList() ?: mutableListOf()
        val bVisibleAnnotations = b.visibleAnnotations?.toMutableList() ?: mutableListOf()
        if (aVisibleAnnotations.size != bVisibleAnnotations.size) return false
        aVisibleAnnotations.removeIf {
            bVisibleAnnotations.any { it2 ->
                if (areAnnotationNodesEqual(it, it2)) {
                    bVisibleAnnotations.remove(it2)
                    true
                } else {
                    false
                }
            }
        }
        if (aVisibleAnnotations.isNotEmpty()) return false
        if (bVisibleAnnotations.isNotEmpty()) return false
        val aInvisibleAnnotations = a.invisibleAnnotations?.toMutableList() ?: mutableListOf()
        val bInvisibleAnnotations = b.invisibleAnnotations?.toMutableList() ?: mutableListOf()
        if (aInvisibleAnnotations.size != bInvisibleAnnotations.size) return false
        aInvisibleAnnotations.removeIf {
            bInvisibleAnnotations.any { it2 ->
                if (areAnnotationNodesEqual(it, it2)) {
                    bInvisibleAnnotations.remove(it2)
                    true
                } else {
                    false
                }
            }
        }
        if (aInvisibleAnnotations.isNotEmpty()) return false
        if (bInvisibleAnnotations.isNotEmpty()) return false
        val aVisibleTypeAnnotations = a.visibleTypeAnnotations?.toMutableList() ?: mutableListOf()
        val bVisibleTypeAnnotations = b.visibleTypeAnnotations?.toMutableList() ?: mutableListOf()
        if (aVisibleTypeAnnotations.size != bVisibleTypeAnnotations.size) return false
        aVisibleTypeAnnotations.removeIf {
            bVisibleTypeAnnotations.any { it2 ->
                if (areAnnotationNodesEqual(it, it2)) {
                    bVisibleTypeAnnotations.remove(it2)
                    true
                } else {
                    false
                }
            }
        }
        if (aVisibleTypeAnnotations.isNotEmpty()) return false
        if (bVisibleTypeAnnotations.isNotEmpty()) return false
        val aInvisibleTypeAnnotations = a.invisibleTypeAnnotations?.toMutableList() ?: mutableListOf()
        val bInvisibleTypeAnnotations = b.invisibleTypeAnnotations?.toMutableList() ?: mutableListOf()
        if (aInvisibleTypeAnnotations.size != bInvisibleTypeAnnotations.size) return false
        aInvisibleTypeAnnotations.removeIf {
            bInvisibleTypeAnnotations.any { it2 ->
                if (areAnnotationNodesEqual(it, it2)) {
                    bInvisibleTypeAnnotations.remove(it2)
                    true
                } else {
                    false
                }
            }
        }
        if (aInvisibleTypeAnnotations.isNotEmpty()) return false
        if (bInvisibleTypeAnnotations.isNotEmpty()) return false
        val aAttributes = a.attrs?.toMutableList() ?: mutableListOf()
        val bAttributes = b.attrs?.toMutableList() ?: mutableListOf()
        if (aAttributes.size != bAttributes.size) return false
        aAttributes.removeIf {
            bAttributes.any { it2 ->
                if (areAttributesEqual(it, it2)) {
                    bAttributes.remove(it2)
                    true
                } else {
                    false
                }
            }
        }
        if (aAttributes.isNotEmpty()) return false
        if (bAttributes.isNotEmpty()) return false

        // check content
        if (a.instructions.size() != b.instructions.size()) return false
        val aInstructions = a.instructions
        val bInstructions = b.instructions
        // label-bearing insns compare by target *index* — LabelNode
        // identity only holds within one list
        val aLabels = labelIndexes(aInstructions)
        val bLabels = labelIndexes(bInstructions)
        for (i in 0 until aInstructions.size()) {
            if (!areInstructionsEqual(
                    aInstructions[i],
                    bInstructions[i],
                    aLabels, bLabels
                )
            ) throw IllegalStateException("Instructions are not equal: ${aInstructions[i]} != ${bInstructions[i]} at index $i in ${a.name} ${a.desc} (${a.instructions.size()} instructions) and ${b.name} ${b.desc} (${b.instructions.size()} instructions)")
        }

        return true
    }

    private fun labelIndexes(list: InsnList): Map<LabelNode, Int> {
        val map = HashMap<LabelNode, Int>()
        for (i in 0 until list.size()) {
            (list[i] as? LabelNode)?.let { map[it] = i }
        }
        return map
    }

    /** Structural label equality — two labels at the same list
     *  index are the "same" position. */
    private fun labelsEqual(a: LabelNode, b: LabelNode,
                            aLabels: Map<LabelNode, Int>,
                            bLabels: Map<LabelNode, Int>) =
        aLabels[a] == bLabels[b]

    fun areAnnotationNodesEqual(a: AnnotationNode, b: AnnotationNode): Boolean {
        return a.desc == b.desc // TODO: check values, maybe?
    }

    fun areAttributesEqual(a: Attribute, b: Attribute): Boolean {
        return a.type == b.type // TODO: check values, maybe?
    }

    /** Operand-aware instruction equality — opcode, node type,
     *  and every operand field of the concrete insn subtype.
     *  Label targets compare by list index (see the
     *  [areMethodNodesEqual] label maps). Pseudo-insns that carry
     *  no semantic content ([LabelNode], [FrameNode]) match by
     *  type alone — frames are derived metadata and legitimately
     *  differ between otherwise-identical bodies. */
    fun areInstructionsEqual(
        a: AbstractInsnNode, b: AbstractInsnNode,
        aLabels: Map<LabelNode, Int> = emptyMap(),
        bLabels: Map<LabelNode, Int> = emptyMap(),
    ): Boolean {
        if (a.opcode != b.opcode) return false
        if (a.type != b.type) return false
        return when (a) {
            is LabelNode, is FrameNode -> true
            is FieldInsnNode -> (b as FieldInsnNode).let {
                a.owner == it.owner && a.name == it.name &&
                        a.desc == it.desc }
            is MethodInsnNode -> (b as MethodInsnNode).let {
                a.owner == it.owner && a.name == it.name &&
                        a.desc == it.desc && a.itf == it.itf }
            is InvokeDynamicInsnNode -> (b as InvokeDynamicInsnNode).let {
                a.name == it.name && a.desc == it.desc &&
                        a.bsm == it.bsm &&
                        a.bsmArgs.contentEquals(it.bsmArgs) }
            is JumpInsnNode -> labelsEqual(
                a.label, (b as JumpInsnNode).label, aLabels, bLabels)
            is VarInsnNode -> a.`var` == (b as VarInsnNode).`var`
            is IincInsnNode -> (b as IincInsnNode).let {
                a.`var` == it.`var` && a.incr == it.incr }
            is TypeInsnNode -> a.desc == (b as TypeInsnNode).desc
            is IntInsnNode -> a.operand == (b as IntInsnNode).operand
            is LdcInsnNode -> a.cst == (b as LdcInsnNode).cst
            is TableSwitchInsnNode -> (b as TableSwitchInsnNode).let {
                a.min == it.min && a.max == it.max &&
                        labelsEqual(a.dflt, it.dflt, aLabels, bLabels) &&
                        a.labels.size == it.labels.size &&
                        a.labels.zip(it.labels).all { (x, y) ->
                            labelsEqual(x, y, aLabels, bLabels) } }
            is LookupSwitchInsnNode -> (b as LookupSwitchInsnNode).let {
                a.keys == it.keys &&
                        labelsEqual(a.dflt, it.dflt, aLabels, bLabels) &&
                        a.labels.size == it.labels.size &&
                        a.labels.zip(it.labels).all { (x, y) ->
                            labelsEqual(x, y, aLabels, bLabels) } }
            is MultiANewArrayInsnNode -> (b as MultiANewArrayInsnNode).let {
                a.desc == it.desc && a.dims == it.dims }
            is LineNumberNode -> a.line == (b as LineNumberNode).line
            // InsnNode (no operands) and anything else: type+opcode
            // already proved equal
            else -> true
        }
    }

    fun areClassMetadataEqual(a: ClassNode, b: ClassNode): Boolean =
        classMetadataDiffs(a, b).isEmpty()

    /** Field-level metadata diffs between a client/server class
     *  pair — surfaced in the merge error so a mismatch names
     *  the field and both values. */
    fun classMetadataDiffs(a: ClassNode, b: ClassNode): List<String> {
        val diffs = mutableListOf<String>()
        fun <T> field(name: String, x: T, y: T) {
            if (x != y) diffs.add("$name: client=$x server=$y")
        }
        if (a.access and Opcodes.ACC_STATIC !=
            b.access and Opcodes.ACC_STATIC
        ) diffs.add(
            "static flag: client=${
                (a.access and Opcodes.ACC_STATIC) != 0
            } server=${
                (b.access and Opcodes.ACC_STATIC) != 0
            }"
        )
        field("name", a.name, b.name)
        field("superName", a.superName, b.superName)
        field("outerClass", a.outerClass, b.outerClass)
        field("outerMethod", a.outerMethod, b.outerMethod)
        field("outerMethodDesc", a.outerMethodDesc, b.outerMethodDesc)
        field("nestHostClass", a.nestHostClass, b.nestHostClass)
        return diffs
    }

    fun areInnerClassNodesEqual(a: InnerClassNode, b: InnerClassNode): Boolean {
        if (a.name != b.name) return false
        if (a.outerName != b.outerName) return false
        if (a.innerName != b.innerName) return false
        if (a.access != b.access) return false
        return true
    }

    fun areRecordComponentNodesEqual(a: RecordComponentNode, b: RecordComponentNode): Boolean {
        if (a.name != b.name) return false
        if (a.descriptor != b.descriptor) return false
        val aVisibleAnnotations = a.visibleAnnotations?.toMutableList() ?: mutableListOf()
        val bVisibleAnnotations = b.visibleAnnotations?.toMutableList() ?: mutableListOf()
        if (aVisibleAnnotations.size != bVisibleAnnotations.size) return false
        aVisibleAnnotations.removeIf {
            bVisibleAnnotations.any { it2 ->
                if (areAnnotationNodesEqual(it, it2)) {
                    bVisibleAnnotations.remove(it2)
                    true
                } else {
                    false
                }
            }
        }
        if (aVisibleAnnotations.isNotEmpty()) return false
        if (bVisibleAnnotations.isNotEmpty()) return false
        val aInvisibleAnnotations = a.invisibleAnnotations?.toMutableList() ?: mutableListOf()
        val bInvisibleAnnotations = b.invisibleAnnotations?.toMutableList() ?: mutableListOf()
        if (aInvisibleAnnotations.size != bInvisibleAnnotations.size) return false
        aInvisibleAnnotations.removeIf {
            bInvisibleAnnotations.any { it2 ->
                if (areAnnotationNodesEqual(it, it2)) {
                    bInvisibleAnnotations.remove(it2)
                    true
                } else {
                    false
                }
            }
        }
        if (aInvisibleAnnotations.isNotEmpty()) return false
        if (bInvisibleAnnotations.isNotEmpty()) return false
        val aVisibleTypeAnnotations = a.visibleTypeAnnotations?.toMutableList() ?: mutableListOf()
        val bVisibleTypeAnnotations = b.visibleTypeAnnotations?.toMutableList() ?: mutableListOf()
        if (aVisibleTypeAnnotations.size != bVisibleTypeAnnotations.size) return false
        aVisibleTypeAnnotations.removeIf {
            bVisibleTypeAnnotations.any { it2 ->
                if (areAnnotationNodesEqual(it, it2)) {
                    bVisibleTypeAnnotations.remove(it2)
                    true
                } else {
                    false
                }
            }
        }
        if (aVisibleTypeAnnotations.isNotEmpty()) return false
        if (bVisibleTypeAnnotations.isNotEmpty()) return false
        val aInvisibleTypeAnnotations = a.invisibleTypeAnnotations?.toMutableList() ?: mutableListOf()
        val bInvisibleTypeAnnotations = b.invisibleTypeAnnotations?.toMutableList() ?: mutableListOf()
        if (aInvisibleTypeAnnotations.size != bInvisibleTypeAnnotations.size) return false
        aInvisibleTypeAnnotations.removeIf {
            bInvisibleTypeAnnotations.any { it2 ->
                if (areAnnotationNodesEqual(it, it2)) {
                    bInvisibleTypeAnnotations.remove(it2)
                    true
                } else {
                    false
                }
            }
        }
        if (aInvisibleTypeAnnotations.isNotEmpty()) return false
        if (bInvisibleTypeAnnotations.isNotEmpty()) return false
        val aAttributes = a.attrs?.toMutableList() ?: mutableListOf()
        val bAttributes = b.attrs?.toMutableList() ?: mutableListOf()
        if (aAttributes.size != bAttributes.size) return false
        aAttributes.removeIf {
            bAttributes.any { it2 ->
                if (areAttributesEqual(it, it2)) {
                    bAttributes.remove(it2)
                    true
                } else {
                    false
                }
            }
        }
        if (aAttributes.isNotEmpty()) return false
        if (bAttributes.isNotEmpty()) return false
        return true
    }
}
