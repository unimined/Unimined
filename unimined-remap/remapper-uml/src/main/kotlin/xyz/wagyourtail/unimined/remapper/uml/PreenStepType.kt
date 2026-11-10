package xyz.wagyourtail.unimined.remapper.uml

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.InsnList
import org.objectweb.asm.tree.InsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.VarInsnNode
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
 * `preen` — Ornithe's obscure-obfuscation undoer
 * (`net.ornithemc.preen.Preen`): splits ProGuard-merged bridge methods.
 *
 * ProGuard-era jars fold a javac bridge into its target: call sites
 * still invoke `name(callDesc)` while the class only declares
 * `name(realDesc)`. Clean bytecode would carry a synthetic
 * `ACC_BRIDGE` delegate; the obfuscated jar relies on the merged
 * method's dual role. This step restores the javac shape — for every
 * virtual call-site descriptor with no declared method of the same
 * name+descriptor, a bridge is synthesized on the declaring class:
 * load `this`, load args per the call descriptor, `INVOKEVIRTUAL` the
 * real method, return.
 *
 * Only same-arity descriptor pairs are bridged (a javac bridge widens
 * parameter/return types, never arity). Call-site owners that are
 * interfaces or that declare no candidate are skipped — the
 * synthesized bridge lives on the class that declares the real
 * method, mirroring javac's placement.
 */
class PreenStepType : RemapStepType<PreenStepSpec> {

    override val name = "ornithe-preen"
    override val stepName = "preen"
    override val specType = PreenStepSpec::class
    override val surfaces = setOf(RemapSurface.JAR, RemapSurface.DECOMPILE)

    override fun supports(spec: RemapSpec) = true

    override fun create(spec: PreenStepSpec, ctx: StepContext) = ArtifactStep { input, sctx ->
        val out = sctx.workDir.resolve(
            input.fileName.toString().removeSuffix(".jar") + "-preen.jar"
        )

        // owner -> method name -> declared descriptors (virtual methods only)
        val declared = HashMap<String, MutableMap<String, MutableSet<String>>>()
        // owner -> method name -> call-site descriptors
        val calls = HashMap<String, MutableMap<String, MutableSet<String>>>()
        val classes = LinkedHashMap<String, ClassNode>()
        val others = LinkedHashMap<String, ByteArray>()

        JarFile(input.toFile()).use { jar ->
            for (entry in jar.entries()) {
                if (entry.isDirectory) continue
                val bytes = jar.getInputStream(entry).readBytes()
                if (!entry.name.endsWith(".class")) {
                    others[entry.name] = bytes
                    continue
                }
                val node = ClassNode()
                ClassReader(bytes).accept(node, 0)
                classes[node.name] = node
                for (method in node.methods) {
                    if (method.access and (Opcodes.ACC_STATIC or Opcodes.ACC_PRIVATE) != 0) continue
                    declared.getOrPut(node.name, ::HashMap)
                        .getOrPut(method.name, ::HashSet)
                        .add(method.desc)
                    var insn = method.instructions.first
                    while (insn != null) {
                        if (insn is MethodInsnNode &&
                            (insn.opcode == Opcodes.INVOKEVIRTUAL || insn.opcode == Opcodes.INVOKEINTERFACE)
                        ) {
                            calls.getOrPut(insn.owner, ::HashMap)
                                .getOrPut(insn.name, ::HashSet)
                                .add(insn.desc)
                        }
                        insn = insn.next
                    }
                }
            }
        }

        JarOutputStream(Files.newOutputStream(out)).use { os ->
            for ((name, node) in classes) {
                val entry = JarEntry("$name.class")
                os.putNextEntry(entry)
                os.write(
                    if (node.access and Opcodes.ACC_INTERFACE == 0) split(node, declared, calls)
                    else node.toBytes()
                )
                os.closeEntry()
            }
            for ((name, bytes) in others) {
                os.putNextEntry(JarEntry(name))
                os.write(bytes)
                os.closeEntry()
            }
        }
        out
    }

    private fun split(
        node: ClassNode,
        declared: Map<String, Map<String, Set<String>>>,
        calls: Map<String, Map<String, Set<String>>>,
    ): ByteArray {
        val missing = calls[node.name] ?: return node.toBytes()
        var changed = false
        for ((mName, callDescs) in missing) {
            val declaredDescs = declared[node.name]?.get(mName) ?: continue
            for (callDesc in callDescs) {
                if (callDesc in declaredDescs) continue
                val target = declaredDescs.firstOrNull {
                    Type.getArgumentTypes(it).size ==
                            Type.getArgumentTypes(callDesc).size
                } ?: continue
                node.methods.add(bridge(node.name, mName, callDesc, target))
                changed = true
            }
        }
        return if (changed) node.toBytes() else node.toBytes()
    }

    private fun ClassNode.toBytes(): ByteArray {
        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        accept(writer)
        return writer.toByteArray()
    }

    private fun bridge(
        owner: String, name: String, callDesc: String, targetDesc: String
    ): MethodNode {
        val method = MethodNode(
            Opcodes.ASM9,
            Opcodes.ACC_PUBLIC or Opcodes.ACC_BRIDGE or Opcodes.ACC_SYNTHETIC,
            name, callDesc, null, null
        )
        val list = InsnList()
        list.add(VarInsnNode(Opcodes.ALOAD, 0))
        var slot = 1
        for (t in Type.getArgumentTypes(callDesc)) {
            list.add(VarInsnNode(t.getOpcode(Opcodes.ILOAD), slot))
            slot += t.size
        }
        list.add(MethodInsnNode(Opcodes.INVOKEVIRTUAL, owner, name, targetDesc, false))
        list.add(InsnNode(Type.getReturnType(targetDesc).getOpcode(Opcodes.IRETURN)))
        method.instructions = list
        method.visitMaxs(-1, -1)
        return method
    }
}
