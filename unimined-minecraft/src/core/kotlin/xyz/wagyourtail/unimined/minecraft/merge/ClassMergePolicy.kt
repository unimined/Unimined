package xyz.wagyourtail.unimined.minecraft.merge

import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldNode
import org.objectweb.asm.tree.MethodNode

/**
 * Conflict-resolution policy for [ClassMerger] — the seams a
 * divergent-member strategy (bytecode side-dispatch, class renames)
 * needs to override. Supplied via `ClassMerger(policy)`; extensions
 * reach a policy-bound merger through
 * `MergeExtension.ClassContext.classMerger(policy)`.
 *
 * The default [STRICT] preserves the canonical merge behavior:
 * hard-fail on class-level metadata divergence, server node kept on
 * same-slot member conflicts.
 */
interface ClassMergePolicy {

    /**
     * Class-level metadata diverged between the sides (superName,
     * static flag, outer/nest host — see
     * [ClassNodeEquality.classMetadataDiffs]). Return the node to emit as
     * the merged class, or throw to fail the merge.
     */
    fun resolveClassMetadata(
        diffs: List<String>, client: ClassNode, server: ClassNode,
    ): ClassNode

    /**
     * Same name+desc method exists on both sides — return the node
     * to emit. Implementations may synthesize (e.g. a dispatch
     * wrapper carrying both bodies) rather than picking one.
     */
    fun resolveMethod(client: MethodNode, server: MethodNode): MethodNode

    /**
     * Same name+desc field exists on both sides — return the node
     * to emit.
     */
    fun resolveField(client: FieldNode, server: FieldNode): FieldNode

    /** The canonical policy — merge fails on any real divergence:
     *  metadata diffs, and same-slot members that aren't actually
     *  equal (the companion equality checks throw on instruction-
     *  level diffs). Verified-equal slots keep the server node. */
    object STRICT : ClassMergePolicy {

        override fun resolveClassMetadata(
            diffs: List<String>, client: ClassNode, server: ClassNode,
        ): ClassNode = throw IllegalArgumentException(
            "Class metadata is not equal: " + diffs.joinToString("; "))

        override fun resolveMethod(
            client: MethodNode, server: MethodNode,
        ): MethodNode {
            if (!ClassNodeEquality.areMethodNodesEqual(client, server)) {
                throw IllegalStateException(
                    "Methods are not equal: ${client.name} ${client.desc}")
            }
            return server.apply {
                access = ClassMerger.selectWeakerAccess(client.access, access)
            }
        }

        override fun resolveField(
            client: FieldNode, server: FieldNode,
        ): FieldNode {
            if (!ClassNodeEquality.areFieldNodesEqual(client, server)) {
                throw IllegalStateException(
                    "Fields are not equal: ${client.name} ${client.desc}")
            }
            return server.apply {
                access = ClassMerger.selectWeakerAccess(client.access, access)
            }
        }
    }
}
