package xyz.wagyourtail.unimined.minecraft.merge

import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodNode

/**
 * Bytecode side-dispatch merger — layer 2 of the deferred
 * merged-artifact design (see `Docs/plan.md`): same name+desc methods
 * whose bodies diverge between sides are not a merge conflict — the
 * merged method carries both behaviors behind a runtime side check,
 * `Boolean.getBoolean(sideProperty)` (truthy picks server; run
 * configs set `-D<sideProperty>` on dedicated-server launches — no
 * marker class needed, which keeps it usable on conversion dists).
 *
 * The splice itself is delegated to a [DispatchSplicer]:
 *
 *  - [DiffDispatchSplicer] (default) — diffs the two bodies, emits
 *    shared bytecode once, and wraps only divergent runs in side
 *    branches that rejoin the shared continuation. Used when the
 *    cuts/rejoins are provably verifier-clean (equal analyzed frames
 *    at every shared position, contained control flow, contained
 *    try/catch, pinned uninitialized-object producers).
 *  - [WholeBodySplicer] — the guaranteed-correct fallback:
 *
 *        ldc prop; getBoolean; ifeq L_client
 *        <server body>
 *      L_client:
 *        <client body>
 *
 *    Each body is self-exiting, so no rejoin frame is needed; labels,
 *    jumps, switches and try/catch move wholesale, and the disjoint
 *    branches share local slots. Inlining — rather than calling
 *    renamed `name$unimined$side` copies — is what makes `<init>`
 *    work: each branch's `invokespecial <init>` stays inside a
 *    constructor, and the verifier accepts per-branch init calls
 *    (`this` is still `uninitializedThis` at the branch point, which
 *    the injected initial frames preserve). `<clinit>` merges the
 *    same way.
 *
 * What this does NOT cover (documented merge limits — the policy
 * throws rather than silently mis-merging):
 *
 *  - class-level metadata divergence (superclass, interfaces,
 *    signature) — layer 3's `Foo$server` renames + transitive
 *    reference rewrite, not implemented yet.
 *  - abstract/native "body" divergence — nothing to splice.
 *  - indy-bearing bodies ride on the class `attrs` union, which only
 *    dedupes attributes by type — a pre-existing merger gap (pre-51
 *    targets have no indy anyway).
 */
class DispatchMerger(
    /** System property the spliced methods read — truthy picks the
     *  server body (`Boolean.getBoolean`). */
    private val sideProperty: String = "unimined.server",
    /** Sectioned splice attempted before the whole-body wrap;
     *  pass [WholeBodySplicer] to disable diffing. */
    private val splicer: DispatchSplicer =
        DiffDispatchSplicer(sideProperty),
) : MergeExtension {

    override fun mergeClass(
        name: String, client: ClassNode?, server: ClassNode?,
        ctx: MergeExtension.ClassContext,
    ): ClassNode? {
        // single-side classes keep the union/marker path
        if (client == null || server == null) return null
        return ctx.classMerger(
            DispatchPolicy(name, maxOf(client.version, server.version),
                sideProperty, splicer)
        ).accept(client, server)
    }

    private class DispatchPolicy(
        private val owner: String,
        private val classVersion: Int,
        private val sideProperty: String,
        private val splicer: DispatchSplicer,
    ) : ClassMergePolicy {

        override fun resolveClassMetadata(
            diffs: List<String>, client: ClassNode, server: ClassNode,
        ): ClassNode = ClassMergePolicy.STRICT
            .resolveClassMetadata(diffs, client, server)

        override fun resolveField(
            client: org.objectweb.asm.tree.FieldNode,
            server: org.objectweb.asm.tree.FieldNode,
        ): org.objectweb.asm.tree.FieldNode =
            ClassMergePolicy.STRICT.resolveField(client, server)

        override fun resolveMethod(
            client: MethodNode, server: MethodNode,
        ): MethodNode {
            // areMethodNodesEqual THROWS on instruction divergence
            // (that's STRICT's failure report) — for dispatch, a
            // thrown inequality is just a splice candidate
            val equal = try {
                ClassNodeEquality.areMethodNodesEqual(client, server)
            } catch (e: IllegalStateException) {
                false
            }
            if (equal) {
                return server.apply {
                    access = ClassMerger.selectWeakerAccess(
                        client.access, access)
                }
            }
            val abstract = Opcodes.ACC_ABSTRACT or Opcodes.ACC_NATIVE
            require(client.access and abstract == 0 &&
                    server.access and abstract == 0) {
                "abstract/native divergence cannot be side-dispatched: " +
                        "$owner.${client.name}${client.desc}"
            }
            return splicer.splice(owner, classVersion, client, server)
                ?: WholeBodySplicer(sideProperty)
                    .splice(owner, classVersion, client, server)!!
        }
    }
}
