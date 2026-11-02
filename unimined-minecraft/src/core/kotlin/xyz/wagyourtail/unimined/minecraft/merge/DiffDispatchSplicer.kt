package xyz.wagyourtail.unimined.minecraft.merge

import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.AbstractInsnNode
import org.objectweb.asm.tree.FieldInsnNode
import org.objectweb.asm.tree.FrameNode
import org.objectweb.asm.tree.IincInsnNode
import org.objectweb.asm.tree.InsnList
import org.objectweb.asm.tree.InsnNode
import org.objectweb.asm.tree.IntInsnNode
import org.objectweb.asm.tree.InvokeDynamicInsnNode
import org.objectweb.asm.tree.JumpInsnNode
import org.objectweb.asm.tree.LabelNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.LocalVariableNode
import org.objectweb.asm.tree.LookupSwitchInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.MultiANewArrayInsnNode
import org.objectweb.asm.tree.TableSwitchInsnNode
import org.objectweb.asm.tree.TryCatchBlockNode
import org.objectweb.asm.tree.TypeInsnNode
import org.objectweb.asm.tree.VarInsnNode
import org.objectweb.asm.tree.analysis.Analyzer
import org.objectweb.asm.tree.analysis.BasicInterpreter
import org.objectweb.asm.tree.analysis.BasicValue
import org.objectweb.asm.tree.analysis.Frame
import org.objectweb.asm.tree.analysis.Interpreter
import org.objectweb.asm.tree.analysis.SimpleVerifier

/**
 * Targeted splice for [DispatchMerger] — the "nice diff" strategy:
 * diff the two bodies, emit shared runs once, and wrap only the
 * divergent runs in `if (side)` branches rejoining into the shared
 * continuation:
 *
 *     <shared>
 *     ldc prop; getBoolean; ifeq L_client
 *     <server-only run>        ; goto L_join
 *   L_client:
 *     <client-only run>
 *   L_join:
 *     <shared>
 *
 * A splice is emitted only when every cut/rejoin is provably
 * verifier-clean — otherwise `null` and the caller falls back to
 * [WholeBodySplicer]. The checks, in order:
 *
 * 1. **Alignment** — LCS over the real-insn sequences. Jumps match
 *    label-agnostically first; a fixpoint then unaligns every shared
 *    jump/switch whose targets don't land on aligned positions, and
 *    unaligns the coverage of any try/catch that lacks an equivalent
 *    range+handler+type on the other side (so every emitted tcb is
 *    either shared identically or fully inside one side's run).
 * 2. **Frame equality at every shared position** — an ASM
 *    [Analyzer] pass per side with an interpreter tracking
 *    `uninitializedThis` and which NEW produced each uninitialized
 *    object. Aligned pairs must carry equal frames — one rule covers
 *    cuts (identical shared prefixes produce identical state) and
 *    rejoins (the first shared pair after a divergent run).
 *    Divergent `<init>` bodies whose init calls sit at different
 *    points produce mismatched `this` types at the rejoin →
 *    fallback, exactly as required.
 * 3. **Containment** — inside a divergent run, label operands may
 *    target only that same run or a shared position ("side exit").
 *    An escape is allowed only when the departing frame equals the
 *    target's frame on that side; server escapes land on the merged
 *    position through a synthesized anchor label.
 *
 * For classVersion ≥ 51, F_NEW frames are emitted at every
 * synthesized basic-block boundary (slice heads, joins, escape
 * targets lacking a client frame) from the analyzed state —
 * including `uninitialized` items pointing at the shared NEW's
 * merged label, valid only because the analysis pins the producer
 * to an aligned instruction on both sides.
 *
 * Known limits (all → whole-body fallback): jsr/ret, LCS table over
 * [LCS_CELL_LIMIT], unaligned uninit producers at boundaries.
 */
internal class DiffDispatchSplicer(
    private val sideProperty: String,
) : DispatchSplicer {

    private companion object {
        const val LCS_CELL_LIMIT = 4_000_000L
    }

    /** Thrown to unwind to the whole-body fallback. */
    private class Bail : Exception()

    /** One side's method under analysis. Positions are real-insn
     *  indices (opcode != -1); [listPos] maps them to InsnList
     *  indices for frames and slicing. */
    private class Side(
        val method: MethodNode,
    ) {
        val insns: List<AbstractInsnNode> =
            method.instructions.toArray().filter { it.opcode != -1 }
        val listPos: IntArray
        /** LabelNode → index of the next real insn (or size = end). */
        val labelPos: Map<LabelNode, Int>
        val mate: IntArray

        init {
            val lp = HashMap<LabelNode, Int>()
            val pos = IntArray(insns.size)
            var real = 0
            val all = method.instructions.toArray()
            for (i in all.indices) {
                val n = all[i]
                if (n is LabelNode) lp[n] = real
                else if (n.opcode != -1) pos[real++] = i
            }
            labelPos = lp
            listPos = pos
            mate = IntArray(insns.size) { -1 }
        }

        /** InsnList range covering real insns [i0,i1) — inter-insn
         *  pseudos attach to the following real insn, so a slice
         *  starts right after the previous real insn. */
        fun sliceStart(i: Int) = if (i == 0) 0 else listPos[i - 1] + 1
        fun sliceEnd(i: Int) =
            if (i >= insns.size) method.instructions.size()
            else sliceStart(i)

        fun sharedPos(p: Int) = p < insns.size && mate[p] >= 0
    }

    private fun labelTargets(i: AbstractInsnNode): List<LabelNode> =
        when (i) {
            is JumpInsnNode -> listOf(i.label)
            is TableSwitchInsnNode -> listOf(i.dflt) + i.labels
            is LookupSwitchInsnNode -> listOf(i.dflt) + i.labels
            else -> emptyList()
        }

    /** Operand equality for diff tokens — label operands are
     *  wildcard (validated post-alignment). */
    private fun tokensEqual(
        a: AbstractInsnNode, b: AbstractInsnNode,
    ): Boolean {
        if (a.opcode != b.opcode || a.type != b.type) return false
        return when (a) {
            is JumpInsnNode -> true
            is FieldInsnNode -> (b as FieldInsnNode).let {
                a.owner == it.owner && a.name == it.name &&
                        a.desc == it.desc }
            is MethodInsnNode -> (b as MethodInsnNode).let {
                a.owner == it.owner && a.name == it.name &&
                        a.desc == it.desc && a.itf == it.itf }
            is InvokeDynamicInsnNode -> (b as InvokeDynamicInsnNode).let {
                a.name == it.name && a.desc == it.desc && a.bsm == it.bsm &&
                        a.bsmArgs.contentEquals(it.bsmArgs) }
            is VarInsnNode -> a.`var` == (b as VarInsnNode).`var`
            is IincInsnNode -> (b as IincInsnNode).let {
                a.`var` == it.`var` && a.incr == it.incr }
            is TypeInsnNode -> a.desc == (b as TypeInsnNode).desc
            is IntInsnNode -> a.operand == (b as IntInsnNode).operand
            is LdcInsnNode -> a.cst == (b as LdcInsnNode).cst
            is TableSwitchInsnNode -> (b as TableSwitchInsnNode).let {
                a.min == it.min && a.max == it.max &&
                        a.labels.size == it.labels.size }
            is LookupSwitchInsnNode -> (b as LookupSwitchInsnNode).let {
                a.keys == it.keys && a.labels.size == it.labels.size }
            is MultiANewArrayInsnNode -> (b as MultiANewArrayInsnNode).let {
                a.desc == it.desc && a.dims == it.dims }
            else -> true
        }
    }

    /** LCS alignment → `mate` arrays (index → partner or -1). */
    private fun align(a: Side, b: Side): Boolean {
        val n = a.insns.size; val m = b.insns.size
        if (n.toLong() * m > LCS_CELL_LIMIT) return false
        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) {
            for (j in m - 1 downTo 0) {
                dp[i][j] = if (tokensEqual(a.insns[i], b.insns[j]))
                    dp[i + 1][j + 1] + 1
                else maxOf(dp[i + 1][j], dp[i][j + 1])
            }
        }
        var i = 0; var j = 0
        while (i < n && j < m) {
            if (tokensEqual(a.insns[i], b.insns[j]) &&
                dp[i][j] == dp[i + 1][j + 1] + 1) {
                a.mate[i] = j; b.mate[j] = i; i++; j++
            } else if (dp[i + 1][j] >= dp[i][j + 1]) i++ else j++
        }
        return true
    }

    /** A jump/switch pair is consistent when every target position
     *  aligns pairwise — end positions only match each other. */
    private fun jumpsConsistent(
        a: Side, b: Side, ai: Int, bi: Int,
    ): Boolean {
        val ta = labelTargets(a.insns[ai])
        val tb = labelTargets(b.insns[bi])
        if (ta.size != tb.size) return false
        return ta.indices.all { k ->
            val pa = a.labelPos[ta[k]]; val pb = b.labelPos[tb[k]]
            when {
                pa == null || pb == null -> false
                pa == a.insns.size -> pb == b.insns.size
                else -> a.mate[pa] == pb
            }
        }
    }

    private fun unalignBadJumps(a: Side, b: Side): Boolean {
        var changed = false
        for (i in a.mate.indices) {
            val j = a.mate[i]
            if (j >= 0 && labelTargets(a.insns[i]).isNotEmpty() &&
                !jumpsConsistent(a, b, i, j)) {
                a.mate[i] = -1; b.mate[j] = -1; changed = true
            }
        }
        return changed
    }

    /** Position [p] on side [x] corresponds to position [q] on side
     *  [y] — both end-of-method, or an aligned pair. */
    private fun posCorresponds(x: Side, p: Int, y: Side, q: Int) =
        if (p == x.insns.size) q == y.insns.size
        else p < x.insns.size && x.mate[p] == q

    /** A tcb is shared iff the other side carries an equivalent one
     *  (same type, corresponding range+handler). Anything else can't
     *  straddle a cut — unalign its covered positions (and handler)
     *  so it lands inside one gap. */
    private fun widenTcbs(x: Side, y: Side): Boolean {
        var changed = false
        for (t in x.method.tryCatchBlocks) {
            val s = x.labelPos[t.start] ?: throw Bail()
            val e = x.labelPos[t.end] ?: throw Bail()
            val h = x.labelPos[t.handler] ?: throw Bail()
            val shared = y.method.tryCatchBlocks.any { o ->
                o.type == t.type &&
                        posCorresponds(x, s, y, y.labelPos[o.start]
                            ?: throw Bail()) &&
                        posCorresponds(x, e, y, y.labelPos[o.end]
                            ?: throw Bail()) &&
                        posCorresponds(x, h, y, y.labelPos[o.handler]
                            ?: throw Bail())
            }
            if (!shared) {
                for (p in s until e) unalign(x, y, p)
                if (h < x.insns.size) unalign(x, y, h)
                changed = true
            }
        }
        return changed
    }

    private fun unalign(x: Side, y: Side, p: Int) {
        val q = x.mate[p]
        if (q >= 0) { x.mate[p] = -1; y.mate[q] = -1 }
    }

    /** Post-fixpoint check: every tcb is either shared (equivalent
     *  on both sides) or fully inside one gap on its own side. */
    private fun tcbsResolvable(x: Side, y: Side): Boolean {
        for (t in x.method.tryCatchBlocks) {
            val s = x.labelPos[t.start]!!; val e = x.labelPos[t.end]!!
            val h = x.labelPos[t.handler]!!
            val shared = y.method.tryCatchBlocks.any { o ->
                o.type == t.type &&
                        posCorresponds(x, s, y, y.labelPos[o.start]!!) &&
                        posCorresponds(x, e, y, y.labelPos[o.end]!!) &&
                        posCorresponds(x, h, y, y.labelPos[o.handler]!!)
            }
            if (shared) continue
            // fully inside one gap — range and handler all unaligned
            // and contiguous
            val pts = (s until e).toList() + listOf(h)
            if (pts.any { x.sharedPos(it) }) return false
            val lo = minOf(s, h); val hi = maxOf(e - 1, h)
            if (lo < 0 || (lo..hi).any { x.sharedPos(it) }) return false
        }
        return true
    }

    private fun hasSubroutine(m: MethodNode): Boolean =
        m.instructions.toArray().any {
            it.opcode == Opcodes.JSR || it.opcode == Opcodes.RET }

    private sealed class Section {
        class Shared(
            val a0: Int, val a1: Int,
            val b0: Int, val b1: Int) : Section()
        class Divergent(
            val a0: Int, val a1: Int,
            val b0: Int, val b1: Int) : Section()
    }

    private fun sectionsOf(a: Side, b: Side): List<Section> {
        val out = mutableListOf<Section>()
        var i = 0; var j = 0
        while (i < a.insns.size || j < b.insns.size) {
            if (i < a.insns.size && j < b.insns.size &&
                a.mate[i] == j) {
                var i1 = i; var j1 = j
                while (i1 < a.insns.size && j1 < b.insns.size &&
                    a.mate[i1] == j1) { i1++; j1++ }
                out += Section.Shared(i, i1, j, j1)
                i = i1; j = j1
            } else {
                var i1 = i; var j1 = j
                while (i1 < a.insns.size && a.mate[i1] < 0) i1++
                while (j1 < b.insns.size && b.mate[j1] < 0) j1++
                out += Section.Divergent(i, i1, j, j1)
                i = i1; j = j1
            }
        }
        return out
    }

    /** Per-side analysis result: frames + the real-insn indices of
     *  this-init calls (`<init>` only — empty otherwise). */
    private class Analysis(
        val frames: Array<out Frame<UValue>?>,
        val thisInitIdxs: Set<Int>,
    )

    private fun analyze(
        owner: String, side: Side,
    ): Analysis? = try {
        val interp = UInterpreter(
            owner, side.method.name == "<init>")
        @Suppress("UNCHECKED_CAST")
        val frames = Analyzer(interp).analyze(owner, side.method)
                as Array<out Frame<UValue>?>
        Analysis(frames, interp.thisInitCalls.mapNotNull { i ->
            side.insns.indexOf(i).takeIf { it >= 0 } }.toSet())
    } catch (e: Exception) {
        null
    }

    /** Frame equality for aligned positions — [a]/[b] give uninit
     *  producers their alignment context: an uninitialized object's
     *  producer NEW must be an aligned pair on both sides (same
     *  merged position → same `uninitialized` verification type). */
    private fun valuesEqual(
        x: UValue?, y: UValue?, a: Side, b: Side,
    ): Boolean {
        if (x == null || y == null) return x == y
        if (x.type != y.type || x.uninitThis != y.uninitThis)
            return false
        if ((x.uninit == null) != (y.uninit == null)) return false
        if (x.uninit != null) {
            val xi = a.insns.indexOf(x.uninit)
            val yi = b.insns.indexOf(y.uninit)
            if (xi < 0 || yi < 0 || a.mate[xi] != yi) return false
        }
        return true
    }

    private fun framesEqual(
        x: Frame<UValue>, y: Frame<UValue>, a: Side, b: Side,
    ): Boolean {
        if (x.locals != y.locals || x.stackSize != y.stackSize)
            return false
        for (i in 0 until x.locals)
            if (!valuesEqual(x.getLocal(i), y.getLocal(i), a, b))
                return false
        for (i in 0 until x.stackSize)
            if (!valuesEqual(x.getStack(i), y.getStack(i), a, b))
                return false
        return true
    }

    override fun splice(
        owner: String, classVersion: Int,
        client: MethodNode, server: MethodNode,
    ): MethodNode? {
        try {
            return doSplice(owner, classVersion, client, server)
        } catch (e: Bail) {
            return null
        }
    }

    private fun doSplice(
        owner: String, classVersion: Int,
        client: MethodNode, server: MethodNode,
    ): MethodNode? {
        if (hasSubroutine(client) || hasSubroutine(server)) return null
        val c = cloneMethod(client)
        val s = cloneMethod(server)
        c.tryCatchBlocks = c.tryCatchBlocks ?: mutableListOf()
        s.tryCatchBlocks = s.tryCatchBlocks ?: mutableListOf()
        // equalize/floor maxes — the analyzer allocates frames from
        // them, and frame arity must match for boundary equality
        // (extra slots analyze as TOP, i.e. a real "unused" check)
        val locals = maxOf(c.maxLocals, s.maxLocals,
            Type.getMethodType(c.desc).argumentTypes.sumOf { it.size } +
                if (c.access and Opcodes.ACC_STATIC == 0) 1 else 0)
        c.maxLocals = locals; s.maxLocals = locals
        c.maxStack = maxOf(c.maxStack, s.maxStack, 4)
        s.maxStack = c.maxStack
        val a = Side(c); val b = Side(s)
        if (a.insns.isEmpty() || b.insns.isEmpty()) return null
        if (!align(a, b)) return null

        // fixpoint: jump consistency and tcb containment each can
        // break the other
        var changed = true
        while (changed) {
            changed = unalignBadJumps(a, b) or unalignBadJumps(b, a)
            changed = widenTcbs(a, b) or widenTcbs(b, a) or changed
        }
        if (!tcbsResolvable(a, b) || !tcbsResolvable(b, a)) return null
        if (a.mate.all { it < 0 }) return null

        // flow analysis — every aligned pair must carry equal
        // frames; covers cuts and rejoins in one rule
        val anA = analyze(owner, a) ?: return null
        val anB = analyze(owner, b) ?: return null
        val af = anA.frames; val bf = anB.frames
        val isCtor = a.method.name == "<init>"
        // this-inits must all be shared — a divergent init point
        // makes `this`'s state diverge at every later boundary
        if (anA.thisInitIdxs.any { a.mate[it] < 0 } ||
            anB.thisInitIdxs.any { b.mate[it] < 0 } ||
            anA.thisInitIdxs.size != anB.thisInitIdxs.size) {
            if (isCtor) return null
        }
        // first shared this-init (client real-insn space) — before
        // it `this` is uninit on both sides, after it initialized
        val sharedInitPos = anA.thisInitIdxs
            .filter { a.mate[it] >= 0 }.minOrNull() ?: Int.MAX_VALUE
        for (i in a.mate.indices) {
            val j = a.mate[i]
            if (j < 0) continue
            val x = af[a.listPos[i]]; val y = bf[b.listPos[j]]
            if (x == null || y == null) {
                if (x != y) return null
            } else if (!framesEqual(x, y, a, b)) return null
        }

        val sections = sectionsOf(a, b)
        val emit = Emit(a, b, af, bf, classVersion,
            isCtor, sharedInitPos)
        emit.validate(sections)
        return emit.produce(sections)
    }

    // ---- emission ----

    private inner class Emit(
        val a: Side, val b: Side,
        val af: Array<out Frame<UValue>?>,
        val bf: Array<out Frame<UValue>?>,
        val version: Int,
        val isCtor: Boolean,
        /** first shared this-init, client real-insn space —
         *  [Int.MAX_VALUE] when none (non-`<init>` methods). */
        val sharedInitPos: Int,
    ) {
        /** client real-insn pos → merged label for shared positions. */
        private val anchors = HashMap<Int, LabelNode>()
        /** client labels at shared positions. */
        private val clientLabelAt = HashMap<Int, LabelNode>()
        /** shared positions needing an injected frame (≥51). */
        private val needFrame = HashSet<Int>()
        /** merged label at method end, lazily created. */
        private var endLabel: LabelNode? = null

        init {
            for ((l, p) in a.labelPos) {
                if (a.sharedPos(p)) clientLabelAt.putIfAbsent(p, l)
            }
        }

        private fun anchorAt(clientPos: Int): LabelNode =
            anchors.getOrPut(clientPos) {
                clientLabelAt[clientPos] ?: LabelNode()
            }

        /** A server-side label ref's merged target: inside a server
         *  gap the node itself is emitted → keep; at a shared
         *  position → the aligned client label/anchor; at end →
         *  the method-end anchor. */
        private fun mapServerLabel(l: LabelNode): LabelNode {
            val pos = b.labelPos[l] ?: throw Bail()
            if (pos == b.insns.size) {
                return endLabel ?: LabelNode().also { endLabel = it }
            }
            val cp = b.mate[pos]
            return if (cp < 0) l else anchorAt(cp)
        }

        fun validate(sections: List<Section>) {
            for (sec in sections) {
                if (sec !is Section.Divergent) continue
                validateGap(a, af, sec.a0, sec.a1, isClient = true)
                validateGap(b, bf, sec.b0, sec.b1, isClient = false)
            }
            // server tcb/lvt refs to shared positions need anchors —
            // collect them now, emission can't retro-insert
            for (t in b.method.tryCatchBlocks) {
                anchorForServer(t.start); anchorForServer(t.end)
                anchorForServer(t.handler)
            }
            for (lv in b.method.localVariables.orEmpty()) {
                anchorForServer(lv.start); anchorForServer(lv.end)
            }
            // uninit producers at boundaries → anchors for ≥51 frames
            for (i in a.mate.indices) {
                val j = a.mate[i]
                if (j < 0) continue
                af[a.listPos[i]]?.let { f -> collectUninit(f, a) }
                bf[b.listPos[j]]?.let { f -> collectUninit(f, b) }
            }
            // ...and at divergent slice heads (entry frames can
            // carry shared-NEW uninit values onto the stack)
            for (sec in sections) {
                if (sec is Section.Divergent) {
                    if (sec.a0 < sec.a1)
                        af[a.listPos[sec.a0]]?.let { collectUninit(it, a) }
                    if (sec.b0 < sec.b1)
                        bf[b.listPos[sec.b0]]?.let { collectUninit(it, b) }
                }
            }
        }

        private fun anchorForServer(l: LabelNode) {
            val pos = b.labelPos[l] ?: return
            if (pos < b.insns.size && b.mate[pos] >= 0)
                anchorAt(b.mate[pos])
            else if (pos == b.insns.size) {
                endLabel ?: run { endLabel = LabelNode() }
            }
        }

        private fun collectUninit(f: Frame<UValue>, x: Side) {
            fun v(vv: UValue?) {
                val u = vv?.uninit ?: return
                val p = x.insns.indexOf(u)
                if (p < 0) return
                val cp = if (x === a) p else b.mate[p]
                if (cp >= 0) anchorAt(cp)
            }
            for (i in 0 until f.locals) v(f.getLocal(i))
            for (i in 0 until f.stackSize) v(f.getStack(i))
        }

        /** A divergent gap's label refs may only target their own
         *  gap or a shared position (escape — frame-checked). */
        private fun validateGap(
            x: Side, xf: Array<out Frame<UValue>?>,
            x0: Int, x1: Int, isClient: Boolean,
        ) {
            for (p in x0 until x1) {
                for (l in labelTargets(x.insns[p])) {
                    val t = x.labelPos[l] ?: throw Bail()
                    if (t >= x0 && t < x1) continue
                    if (t == x.insns.size || x.mate[t] >= 0) {
                        // escape — departing state must equal the
                        // shared target's expected state
                        if (t < x.insns.size) {
                            val f = xf[x.listPos[p]]
                            val tf = xf[x.listPos[t]]
                            if (f == null || tf == null ||
                                !framesEqual(f, tf, a, b)) throw Bail()
                            if (!isClient) {
                                anchorAt(x.mate[t]); needFrame += x.mate[t]
                            }
                        } else if (!isClient) {
                            endLabel ?: run { endLabel = LabelNode() }
                        }
                    } else throw Bail()
                }
            }
        }

        fun produce(sections: List<Section>): MethodNode {
            val s = b.method
            val out = InsnList()
            sections.forEachIndexed { idx, sec ->
                when (sec) {
                    is Section.Shared -> {
                        if (idx != 0)
                            emitBoundaryFrame(out, af, a, sec.a0, sec.a0)
                        emitSlice(out, a, sec.a0, sec.a1)
                    }
                    is Section.Divergent -> emitDivergent(
                        out, sec, idx == sections.lastIndex)
                }
            }
            endLabel?.let { out.add(it) }
            s.instructions = out

            mergeMethodMetadata(s, a.method, mergeTryCatch = false)

            // tcbs: every client tcb verbatim (all client labels are
            // emitted); server tcbs only the ones living inside a
            // server gap — shared tcbs are the client's copy already
            val tcbs = mutableListOf<TryCatchBlockNode>()
            tcbs += a.method.tryCatchBlocks.map { t ->
                TryCatchBlockNode(t.start, t.end, t.handler, t.type) }
            tcbs += b.method.tryCatchBlocks.filter { t ->
                a.method.tryCatchBlocks.none { o ->
                    o.type == t.type &&
                        posCorresponds(b, b.labelPos[t.start]!!, a,
                            a.labelPos[o.start]!!) &&
                        posCorresponds(b, b.labelPos[t.end]!!, a,
                            a.labelPos[o.end]!!) &&
                        posCorresponds(b, b.labelPos[t.handler]!!, a,
                            a.labelPos[o.handler]!!)
                }
            }.map { t ->
                TryCatchBlockNode(mapServerLabel(t.start),
                    mapServerLabel(t.end),
                    mapServerLabel(t.handler), t.type) }
            s.tryCatchBlocks = tcbs

            // LVT: client verbatim; server entries remapped (labels
            // at shared positions → anchors, in-gap labels ride their
            // slice — mapServerLabel handles both)
            s.localVariables = (a.method.localVariables.orEmpty() +
                    b.method.localVariables.orEmpty().map { lv ->
                        LocalVariableNode(lv.name, lv.desc,
                            lv.signature, mapServerLabel(lv.start),
                            mapServerLabel(lv.end), lv.index)
                    }).toMutableList()

            s.maxStack = s.maxStack + 1  // the guard pushes one slot
            return s
        }

        private fun emitDivergent(
            out: InsnList, sec: Section.Divergent, last: Boolean,
        ) {
            val clientLabel = LabelNode()
            val join = LabelNode()
            val aEmpty = sec.a0 >= sec.a1
            val bEmpty = sec.b0 >= sec.b1
            when {
                bEmpty -> {                      // client-only run
                    out.add(LdcInsnNode(sideProperty))
                    out.add(MethodInsnNode(Opcodes.INVOKESTATIC,
                        "java/lang/Boolean", "getBoolean",
                        "(Ljava/lang/String;)Z", false))
                    out.add(JumpInsnNode(Opcodes.IFNE, join))
                    emitBoundaryFrame(out, af, a, sec.a0, sec.a0)
                    emitSlice(out, a, sec.a0, sec.a1)
                    emitJoin(out, join, af, a, sec.a0, sec.a0, last)
                }
                aEmpty -> {                      // server-only run
                    emitSideGuard(out, sideProperty, join)
                    emitBoundaryFrame(out, bf, b, sec.b0, sec.a0)
                    emitSlice(out, b, sec.b0, sec.b1)
                    emitJoin(out, join, bf, b, sec.b0, sec.a0, last)
                }
                else -> {
                    emitSideGuard(out, sideProperty, clientLabel)
                    emitBoundaryFrame(out, bf, b, sec.b0, sec.a0)
                    emitSlice(out, b, sec.b0, sec.b1)
                    val needsJoin = fallsThrough(b, sec.b1)
                    if (needsJoin) {
                        // server must skip the client slice — a
                        // trailing goto can't land past code end
                        if (last) throw Bail()
                        out.add(JumpInsnNode(Opcodes.GOTO, join))
                    }
                    out.add(clientLabel)
                    emitBoundaryFrame(out, af, a, sec.a0, sec.a0)
                    emitSlice(out, a, sec.a0, sec.a1)
                    // join lands before the shared continuation —
                    // its boundary frame comes from the next section
                    if (needsJoin) out.add(join)
                }
            }
        }

        /** Emit the join label — at method end it's not a legal
         *  jump target, so give it a dead exit. */
        private fun emitJoin(
            out: InsnList, join: LabelNode,
            xf: Array<out Frame<UValue>?>, x: Side, p: Int,
            canonPos: Int, last: Boolean,
        ) {
            out.add(join)
            if (!last) return
            // trailing join — a jump to code end is illegal; land it
            // on a dead athrow with the arriving edge's frame
            if (version >= Opcodes.V1_7) {
                val f = xf[x.listPos[p]]
                if (f != null) out.add(frameNodeOf(f, canonPos))
            }
            out.add(InsnNode(Opcodes.ACONST_NULL))
            out.add(InsnNode(Opcodes.ATHROW))
        }

        private fun fallsThrough(x: Side, x1: Int): Boolean {
            val last = x.insns[x1 - 1]
            return when (last.opcode) {
                Opcodes.GOTO, Opcodes.RET,
                Opcodes.IRETURN, Opcodes.LRETURN, Opcodes.FRETURN,
                Opcodes.DRETURN, Opcodes.ARETURN, Opcodes.RETURN,
                Opcodes.ATHROW,
                Opcodes.TABLESWITCH, Opcodes.LOOKUPSWITCH -> false
                else -> true
            }
        }

        /** F_NEW of the analyzed frame at side-[x] pos [p] — ≥51
         *  only. [canonPos] is the boundary's client real-insn index,
         *  used for `this` init-state (uninit vs owner). */
        private fun emitBoundaryFrame(
            out: InsnList, xf: Array<out Frame<UValue>?>,
            x: Side, p: Int, canonPos: Int,
        ) {
            if (version < Opcodes.V1_7) return
            val f = xf[x.listPos[p]] ?: return
            out.add(frameNodeOf(f, canonPos))
        }

        /** Encode a frame value: `uninitThis` is UNINITIALIZED_THIS
         *  only before the shared init; an `uninit` producer emits
         *  its anchor on the *stack* (uninit objects can't live in
         *  locals — a tagged local is provably initialized) and the
         *  plain type in a local slot. */
        private fun frameNodeOf(
            f: Frame<UValue>, canonPos: Int,
        ): FrameNode {
            fun enc(v: UValue?, stack: Boolean): Any = when {
                v == null -> Opcodes.TOP
                v.uninitThis ->
                    if (canonPos < sharedInitPos)
                        Opcodes.UNINITIALIZED_THIS
                    else frameType(v.type!!)
                v.uninit != null && stack ->
                    anchorAt(uninitClientPos(v.uninit!!))
                v.type == null -> Opcodes.TOP
                v.type.sort == Type.OBJECT &&
                    v.type.internalName == "null" -> Opcodes.NULL
                else -> frameType(v.type)
            }
            val locals = (0 until f.locals).map {
                enc(f.getLocal(it), false) }
            val stack = (0 until f.stackSize).map {
                enc(f.getStack(it), true) }
            return FrameNode(Opcodes.F_NEW, locals.size,
                locals.toTypedArray(), stack.size, stack.toTypedArray())
        }

        /** The NEW producing an uninit value — on either side —
         *  maps to its aligned client real-insn position (equality
         *  guaranteed by [valuesEqual]). */
        private fun uninitClientPos(insn: AbstractInsnNode): Int {
            val ai = a.insns.indexOf(insn)
            if (ai >= 0) return ai
            val bi = b.insns.indexOf(insn)
            if (bi >= 0 && b.mate[bi] >= 0) return b.mate[bi]
            throw Bail()
        }

        /** Emit real-insn range [x0,x1) of side [x] — pseudos attach
         *  forward; leading frames dropped (boundary frames cover);
         *  server slices rewrite label refs on the fly. */
        private fun emitSlice(
            out: InsnList, x: Side, x0: Int, x1: Int,
        ) {
            val from = x.sliceStart(x0); val to = x.sliceEnd(x1)
            val isServer = x === b
            var head = true
            var realIdx = x0
            for (li in from until to) {
                val n = x.method.instructions[li]
                if (n.opcode == -1) {
                    if (head && n is FrameNode) continue
                    out.add(n)
                    continue
                }
                // real insn — pending anchors/frames at this pos
                if (!isServer) {
                    anchors[realIdx]?.let { out.add(it) }
                    if (version >= Opcodes.V1_7 &&
                        realIdx in needFrame) {
                        var i = out.size() - 1
                        var sawFrame = false
                        while (i >= 0 && out[i].opcode == -1) {
                            if (out[i] is FrameNode) {
                                sawFrame = true; break
                            }
                            i--
                        }
                        if (!sawFrame)
                            emitBoundaryFrame(out, af, a, realIdx, realIdx)
                    }
                }
                if (isServer) rewriteTargets(n)
                out.add(n)
                head = false
                realIdx++
            }
        }

        private fun rewriteTargets(n: AbstractInsnNode) {
            when (n) {
                is JumpInsnNode -> n.label = mapServerLabel(n.label)
                is TableSwitchInsnNode -> {
                    n.dflt = mapServerLabel(n.dflt)
                    n.labels = n.labels.map { mapServerLabel(it) }
                }
                is LookupSwitchInsnNode -> {
                    n.dflt = mapServerLabel(n.dflt)
                    n.labels = n.labels.map { mapServerLabel(it) }
                }
                else -> {}
            }
        }
    }
}

/** [BasicValue] plus uninitialized-object tracking: which NEW
 *  produced a not-yet-`invokespecial`'d object, and whether the
 *  value is `uninitializedThis`. Boundary frames can then both
 *  *check* init-state equality (mismatch → whole-body fallback)
 *  and *emit* `uninitialized` stack-map items at the producing
 *  NEW's merged position. */
private class UValue(
    type: Type?,
    /** the NEW insn that produced this uninit object, if any. */
    val uninit: AbstractInsnNode? = null,
    val uninitThis: Boolean = false,
) : BasicValue(type) {
    /** Type-equality plus uninit flags — also equal to a plain
     *  [BasicValue] of the same type (the verifier-side checks in
     *  the base interpreter compare against its own values). */
    override fun equals(other: Any?) = when {
        other !is BasicValue -> false
        other is UValue -> type == other.type &&
                uninit == other.uninit && uninitThis == other.uninitThis
        else -> type == other.type
    }
    override fun hashCode() = type?.hashCode() ?: 0
}

private class UInterpreter(
    owner: String, private val isCtor: Boolean,
) : Interpreter<UValue>(Opcodes.ASM9) {
    /** [SimpleVerifier] keeps precise reference types (unlike
     *  [BasicInterpreter]'s REFERENCE_VALUE erasure) — needed for
     *  emitted stack-map frames — while flagging semantically
     *  invalid operations as [AnalyzerException]. */
    private val base = SimpleVerifier(Type.getObjectType(owner),
        Type.getObjectType("java/lang/Object"), false)

    /** invokespecial-`<init>` insns whose objectref was
     *  `uninitializedThis` — this-inits (vs fresh-object inits).
     *  asm-analysis models no init-state transition, so the caller
     *  uses these to partition the method into pre/post-init. */
    val thisInitCalls = mutableSetOf<AbstractInsnNode>()

    /** Wrap a [BasicInterpreter] result, preserving uninit flags of
     *  the input it was derived from. */
    private fun uw(v: BasicValue?, of: UValue? = null): UValue =
        if (of == null) UValue(v?.type)
        else UValue(v?.type, of.uninit, of.uninitThis)

    override fun newValue(type: Type?): UValue = UValue(type)
    override fun newParameterValue(
        isInstanceMethod: Boolean, local: Int, type: Type?,
    ): UValue =
        if (isCtor && local == 0) UValue(type, uninitThis = true)
        else UValue(type)
    override fun newReturnTypeValue(type: Type): UValue? =
        if (type == Type.VOID_TYPE) null else UValue(type)
    override fun newOperation(insn: AbstractInsnNode): UValue {
        val v = base.newOperation(insn)
        return if (insn.opcode == Opcodes.NEW)
            UValue(v.type, uninit = insn) else UValue(v.type)
    }
    override fun copyOperation(
        insn: AbstractInsnNode, value: UValue,
    ): UValue = value
    override fun unaryOperation(
        insn: AbstractInsnNode, value: UValue,
    ): UValue = uw(base.unaryOperation(insn, value), of =
        if (insn.opcode == Opcodes.CHECKCAST) value else null)
    override fun binaryOperation(
        insn: AbstractInsnNode, value1: UValue, value2: UValue,
    ): UValue = uw(base.binaryOperation(insn, value1, value2))
    override fun ternaryOperation(
        insn: AbstractInsnNode, value1: UValue,
        value2: UValue, value3: UValue,
    ): UValue = uw(base.ternaryOperation(insn, value1, value2, value3))
    override fun naryOperation(
        insn: AbstractInsnNode, values: MutableList<out UValue>,
    ): UValue {
        if (insn is MethodInsnNode &&
            insn.opcode == Opcodes.INVOKESPECIAL &&
            insn.name == "<init>" && values.isNotEmpty() &&
            values[0].uninitThis) {
            thisInitCalls += insn
        }
        return uw(base.naryOperation(insn, values))
    }
    override fun returnOperation(
        insn: AbstractInsnNode, value: UValue, expected: UValue,
    ) = base.returnOperation(insn, value, expected)
    override fun merge(v: UValue, w: UValue): UValue =
        if (v == w) v
        else if (v.uninit != null || w.uninit != null ||
            v.uninitThis || w.uninitThis) UValue(null)
        else uw(base.merge(v, w))
}
