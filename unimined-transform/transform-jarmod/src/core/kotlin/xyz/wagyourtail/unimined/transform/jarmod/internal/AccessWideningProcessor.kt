package xyz.wagyourtail.unimined.transform.jarmod.internal

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import xyz.wagyourtail.unimined.transform.processor.JarProcessor
import java.io.Serializable
import java.nio.file.FileSystems
import java.nio.file.Path

/**
 * Widens `protected`/package-private members that are accessed across
 * packages after an obf->named remap.
 *
 * Obfuscated Minecraft places every class in one flat package, so a
 * protected (or package-private) member was package-visible from every
 * call site. A yarn-style named remap moves classes into real packages
 * (e.g. `net.minecraft.entity` vs `net.minecraft.entity.mob`), turning
 * those formerly-legal same-package accesses into illegal cross-package
 * protected accesses — the JVM verifier then fails the caller with
 * `VerifyError: Bad access to protected data`. This runs on the named
 * product jar and promotes any such member to `public`.
 */
class AccessWideningProcessor : JarProcessor {

    override val name = "access-widening"
    // runs on the named product — the packages must already be the
    // runtime ones for the cross-package check to mean anything
    override val preRemap = false

    class Spec : JarProcessor.JarProcessorSpec, Serializable

    override fun buildSpec(context: JarProcessor.SpecContext): JarProcessor.JarProcessorSpec = Spec()
    override fun serializeSpec(spec: JarProcessor.JarProcessorSpec): String = ""
    override fun deserializeSpec(payload: String): JarProcessor.JarProcessorSpec = Spec()

    override fun processJar(
        jar: Path,
        spec: JarProcessor.JarProcessorSpec,
        context: JarProcessor.ProcessorContext
    ) {
        FileSystems.newFileSystem(jar).use { fs ->
            // pass 1 — collect every class + its declared members' access
            val classes = mutableMapOf<String, ClassNode>()
            val memberAccess = mutableMapOf<String, MutableMap<String, Int>>()
            fs.getPath("/").also { root ->
                java.nio.file.Files.walk(root).use { stream ->
                    stream.filter { it.toString().endsWith(".class") }
                        .forEach { p ->
                            val cn = ClassNode()
                            ClassReader(java.nio.file.Files.readAllBytes(p))
                                .accept(cn, 0)
                            classes[cn.name] = cn
                            val members = mutableMapOf<String, Int>()
                            cn.methods.forEach {
                                members["${it.name}${it.desc}"] = it.access
                            }
                            cn.fields.forEach {
                                members["${it.name}:${it.desc}"] = it.access
                            }
                            memberAccess[cn.name] = members
                        }
                }
            }

            // pass 2 — find members accessed from a different package
            // while protected/package-private on the owner. References are
            // often made through a subclass (`invokevirtual Sub.m` for a
            // member declared on Base), so resolve the actual declarer by
            // walking the hierarchy before judging the access. Protected
            // members remain accessible to subclasses across packages
            // (`super.m()` is the common shape); package-private members do
            // not — only the subclass exemption keeps protected access legal.
            val supers = classes.mapValues { it.value.superName }
            fun isSubclassOf(accessor: String, owner: String): Boolean {
                var cur: String? = accessor
                var hops = 0
                while (cur != null && cur != owner && hops++ < 64) {
                    cur = supers[cur]
                }
                return cur == owner
            }
            fun declaringMember(owner: String, key: String): Pair<String, Int>? {
                var cur: String? = owner
                var hops = 0
                while (cur != null && hops++ < 64) {
                    memberAccess[cur]?.get(key)?.let { return cur to it }
                    cur = supers[cur]
                }
                return null
            }

            val widen = mutableSetOf<Pair<String, String>>() // declarer -> memberKey
            for (cn in classes.values) {
                val callerPkg = pkg(cn.name)
                for (m in cn.methods) {
                    for (insn in m.instructions) {
                        val (owner, key) = when (insn) {
                            is MethodInsnNode -> insn.owner to
                                "${insn.name}${insn.desc}"
                            is FieldInsnNode -> insn.owner to
                                "${insn.name}:${insn.desc}"
                            else -> continue
                        }
                        if (owner == cn.name) continue
                        val (declarer, access) = declaringMember(owner, key)
                            ?: continue
                        // private is always scoped; public is fine —
                        // only protected/package-private can break
                        val isProtected = access and Opcodes.ACC_PROTECTED != 0
                        val isProtectedOrPkg =
                            access and (Opcodes.ACC_PROTECTED or
                                Opcodes.ACC_PUBLIC or
                                Opcodes.ACC_PRIVATE) == 0 || isProtected
                        if (!isProtectedOrPkg) continue
                        if (pkg(declarer) == callerPkg) continue
                        if (isProtected && isSubclassOf(cn.name, declarer)) continue
                        widen += declarer to key
                    }
                }
            }

            if (widen.isEmpty()) return
            // pass 3 — widen the flagged members and write the jar back.
            // Widening the declaration alone would make every existing
            // protected/package-private override an illegal visibility
            // reduction, so the promotion propagates down the override
            // hierarchy inside this jar as one consistent visibility change.
            val widenByOwner = widen.groupBy({ it.first }, { it.second })
            for ((owner, keys) in widenByOwner) {
                for (cn in classes.values) {
                    if (cn.name != owner && !isSubclassOf(cn.name, owner)) continue
                    var touched = false
                    cn.methods.forEach {
                        if ("${it.name}${it.desc}" in keys) {
                            it.access = it.access and
                                (Opcodes.ACC_PROTECTED or Opcodes.ACC_PRIVATE).inv() or
                                Opcodes.ACC_PUBLIC
                            touched = true
                        }
                    }
                    cn.fields.forEach {
                        if ("${it.name}:${it.desc}" in keys) {
                            it.access = it.access and
                                (Opcodes.ACC_PROTECTED or Opcodes.ACC_PRIVATE).inv() or
                                Opcodes.ACC_PUBLIC
                            touched = true
                        }
                    }
                    if (touched) {
                        val w = ClassWriter(0)
                        cn.accept(w)
                        java.nio.file.Files.write(
                            fs.getPath("/${cn.name}.class"), w.toByteArray())
                    }
                }
            }
            context.logger.lifecycle(
                "[unimined] access-widening: promoted " +
                    "${widen.size} cross-package member(s) to public in " +
                    "${widenByOwner.size} class(es)")
        }
    }

    private fun pkg(internalName: String): String =
        internalName.substringBeforeLast('/', "")
}
