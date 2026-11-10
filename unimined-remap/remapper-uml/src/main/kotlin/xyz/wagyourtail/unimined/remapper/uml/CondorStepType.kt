package xyz.wagyourtail.unimined.remapper.uml

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.AbstractInsnNode
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.LabelNode
import org.objectweb.asm.tree.LocalVariableNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.analysis.Analyzer
import org.objectweb.asm.tree.analysis.BasicInterpreter
import org.objectweb.asm.tree.analysis.BasicValue
import xyz.wagyourtail.unimined.api.spec.RemapSpec
import xyz.wagyourtail.unimined.remap.spi.ArtifactStep
import xyz.wagyourtail.unimined.remap.spi.RemapStepType
import xyz.wagyourtail.unimined.remap.spi.RemapSurface
import xyz.wagyourtail.unimined.remap.spi.StepContext
import java.nio.file.Files
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream

/**
 * `condor` — Ornithe's local-variable-table generator (after
 * `LocalVariableTableGenerator` in the Mixin project).
 *
 * For each method lacking a `LocalVariableTable`, runs ASM's
 * `Analyzer<BasicValue>` to get per-instruction frames, then emits a
 * variable covering each slot's live range: parameters span the whole
 * body, other slots span first-store → last-use. Synthetic names are
 * `var<slot>` for parameters and `lvt<slot>` for locals — enough for
 * decompilers and for Mixin `@Local` capture resolution at runtime.
 *
 * Extra [LabelNode]s are inserted where a range boundary doesn't already
 * land on a label; labels are free in the emitted class file, so this
 * does not perturb line numbers or frames.
 */
class CondorStepType : RemapStepType<CondorStepSpec> {

    override val name = "ornithe-condor"
    override val stepName = "condor"
    override val specType = CondorStepSpec::class
    override val surfaces = setOf(RemapSurface.JAR, RemapSurface.DECOMPILE)

    override fun supports(spec: RemapSpec) = true

    override fun create(spec: CondorStepSpec, ctx: StepContext) = ArtifactStep { input, sctx ->
        val force = spec.force.getOrElse(false)
        val out = sctx.workDir.resolve(
            input.fileName.toString().removeSuffix(".jar") + "-condor.jar"
        )
        JarFile(input.toFile()).use { jar ->
            JarOutputStream(Files.newOutputStream(out)).use { os ->
                for (entry in jar.entries()) {
                    val next = JarEntry(entry.name)
                    next.time = entry.time
                    os.putNextEntry(next)
                    if (!entry.isDirectory) {
                        val bytes = jar.getInputStream(entry).readBytes()
                        os.write(
                            if (entry.name.endsWith(".class")) generate(bytes, force)
                            else bytes
                        )
                    }
                    os.closeEntry()
                }
            }
        }
        out
    }

    private fun generate(bytes: ByteArray, force: Boolean): ByteArray {
        val node = ClassNode()
        ClassReader(bytes).accept(node, 0)
        var changed = false
        for (method in node.methods) {
            changed = generateFor(node, method, force) || changed
        }
        if (!changed) return bytes
        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        node.accept(writer)
        return writer.toByteArray()
    }

    private fun generateFor(node: ClassNode, method: MethodNode, force: Boolean): Boolean {
        if (method.access and (Opcodes.ACC_ABSTRACT or Opcodes.ACC_NATIVE) != 0) return false
        if (method.instructions.size() == 0) return false
        if (!method.localVariables.isNullOrEmpty() && !force) return false

        val frames = try {
            Analyzer(BasicInterpreter()).analyze(node.name, method)
        } catch (e: Throwable) {
            return false // unanalyzable (dead-code shapes the analyzer rejects) — leave alone
        }
        if (frames == null) return false

        // slots live at method entry (parameters + `this`)
        var argSlots = if (method.access and Opcodes.ACC_STATIC != 0) 0 else 1
        for (t in Type.getArgumentTypes(method.desc)) argSlots += t.size

        val insns = method.instructions
        val live = HashMap<Int, Pair<Int, Int>>() // slot -> (first, last) insn indices
        for (i in 0 until insns.size()) {
            val frame = frames[i] ?: continue
            for (slot in 0 until frame.locals) {
                if (frame.getLocal(slot) == null) continue
                val range = live[slot]
                live[slot] = if (range == null) i to i else range.first to i
            }
        }

        val vars = ArrayList<LocalVariableNode>()
        for ((slot, range) in live.toSortedMap()) {
            val isParam = slot < argSlots
            val first = insns.get(if (isParam) 0 else range.first)
            val after = when {
                isParam -> null // spans whole body — trailing label
                range.second + 1 < insns.size() -> insns.get(range.second + 1)
                else -> null
            }
            val desc = localType(method, slot, range.first, frames)?.descriptor ?: "Ljava/lang/Object;"
            vars += LocalVariableNode(
                if (isParam) "var$slot" else "lvt$slot",
                desc, null,
                labelBefore(method, first),
                after?.let { labelBefore(method, it) } ?: endLabel(method),
                slot
            )
        }
        if (vars.isEmpty()) return false
        method.localVariables = vars
        return true
    }

    /** Descriptor for [slot] taken from its first live frame; falls back
     *  to the parameter type for argument slots and `Object` otherwise. */
    private fun localType(
        method: MethodNode, slot: Int, firstLive: Int,
        frames: Array<org.objectweb.asm.tree.analysis.Frame<BasicValue>?>
    ): Type? {
        var s = 0
        if (method.access and Opcodes.ACC_STATIC == 0) {
            if (slot == 0) return Type.getObjectType("java/lang/Object")
            s = 1
        }
        for (t in Type.getArgumentTypes(method.desc)) {
            if (slot == s) return t
            s += t.size
        }
        return frames[firstLive]?.getLocal(slot)?.type
    }

    /** A label positioned immediately before [insn] — reusing [insn] when
     *  it is itself a label, else inserting a fresh [LabelNode]. */
    private fun labelBefore(method: MethodNode, insn: AbstractInsnNode): LabelNode {
        if (insn is LabelNode) return insn
        val label = LabelNode()
        method.instructions.insertBefore(insn, label)
        return label
    }

    /** Trailing label marking end-of-body scope (the JVMS allows the end
     *  of the code array as a scope end); appended once and reused. */
    private fun endLabel(method: MethodNode): LabelNode {
        val last = method.instructions.last
        if (last is LabelNode) return last
        val label = LabelNode()
        method.instructions.add(label)
        return label
    }
}
