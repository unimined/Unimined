package xyz.wagyourtail.unimined.transform.internal.classtransform

import net.lenni0451.classtransform.TransformerManager
import net.lenni0451.classtransform.additionalclassprovider.FileSystemClassProvider
import net.lenni0451.classtransform.utils.tree.BasicClassProvider
import net.lenni0451.classtransform.transformer.HandlerPosition
import java.io.IOException
import java.nio.file.FileSystem
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes

/**
 * Shared classtransform-based jar patcher (extracted from FG2 dev
 * fixes) — applies annotation-driven transformer classes to matching
 * entries inside an open jar [FileSystem].
 *
 * Transformer classes live on the plugin classpath; targets are jar
 * entry paths, each `exists`-gated so unseen variants no-op. Helper
 * classes listed in [helpers] are spliced into the jar so injected
 * code has something to call at runtime.
 */
object ClassTransformJarFix {

    /**
     * @param transformers transformer class fqcn -> target jar entries
     * @param helpers jar entry paths (classpath resources) to splice in
     */
    fun fix(
        fs: FileSystem,
        transformers: Map<String, List<String>>,
        helpers: List<String> = emptyList()
    ) {
        val provider = LenientClassProvider(FileSystemClassProvider(fs, BasicClassProvider()))
        val manager = TransformerManager(provider)
        // classtransform's default FailStrategy is EXIT — it System.exit()s
        // the host JVM on any unresolved method pattern, which inside a
        // Gradle daemon kill-switch is fatal (cleanroom's rewritten
        // CoreModManager lacks discoverCoreMods and took the daemon with
        // it). CONTINUE logs the failure and keeps going — the 1.x
        // FixFG2* visitors' "absent methods no-op" semantics.
        manager.failStrategy =
            net.lenni0451.classtransform.utils.FailStrategy.CONTINUE
        manager.addInjectionTarget("SUPER_SELF", SelfSuperTarget())
        manager.addCustomAnnotationHandler(CSuperOverrideAnnotationHandler(), HandlerPosition.PRE)
        manager.addCustomAnnotationHandler(CSurgeryStubStripHandler(), HandlerPosition.PRE)
        manager.addCustomAnnotationHandler(CSurgeryAnnotationHandler(), HandlerPosition.POST)
        for (tx in transformers.keys) manager.addTransformer(tx)

        for (entries in transformers.values) {
            for (file in entries) {
                val path = fs.getPath(file)
                if (!path.exists()) continue
                val name = file.removeSuffix(".class").replace('/', '.')
                manager.transform(name, path.readBytes())?.let {
                    path.writeBytes(fixInterfaceInvokes(it, provider))
                }
            }
        }

        for (helper in helpers) {
            splice(fs, helper)
        }
    }

    /**
     * Two repairs on classtransform output before it lands back in
     * the jar:
     *
     * 1. `CStub` emits `INVOKEVIRTUAL` for every non-static member
     * stub — including stubs whose owner is an interface (FML's
     * `ModContainer` is one), which links as
     * `IncompatibleClassChangeError` at runtime. An `invokevirtual`
     * on an interface owner is always invalid, so rewriting it to
     * `invokeinterface` is strictly a repair.
     * 2. `CShadow` resolves the target member but only remaps its
     * *name* at use sites — a stub declared with a placeholder type
     * (`Object` for an era-specific type) leaves method calls and
     * field accesses carrying the stub's desc, which resolves nothing
     * (`NoSuchMethodError`/`NoSuchFieldError`). A same-owner ref that
     * matches no member by desc but exactly one by name gets the real
     * member's desc.
     */
    private fun fixInterfaceInvokes(
        bytes: ByteArray,
        provider: net.lenni0451.classtransform.utils.tree.IClassProvider
    ): ByteArray {
        val node = org.objectweb.asm.tree.ClassNode()
        org.objectweb.asm.ClassReader(bytes).accept(node, 0)
        val accessCache = HashMap<String, Int?>()
        fun accessOf(owner: String): Int? = accessCache.getOrPut(owner) {
            try {
                provider.getClass(owner)?.let {
                    org.objectweb.asm.ClassReader(it).access
                }
            } catch (e: Throwable) { null }
        }
        var changed = false
        for (method in node.methods) {
            for (insn in method.instructions) {
                when (insn) {
                    is org.objectweb.asm.tree.MethodInsnNode -> {
                        if (insn.opcode == org.objectweb.asm.Opcodes.INVOKEVIRTUAL &&
                            ((accessOf(insn.owner) ?: 0) and
                                    org.objectweb.asm.Opcodes.ACC_INTERFACE) != 0
                        ) {
                            insn.opcode = org.objectweb.asm.Opcodes.INVOKEINTERFACE
                            insn.itf = true
                            changed = true
                        }
                        if (insn.owner == node.name &&
                            node.methods.none { it.name == insn.name && it.desc == insn.desc }
                        ) {
                            val candidates = node.methods.filter { it.name == insn.name }
                            if (candidates.size == 1) {
                                insn.desc = candidates[0].desc
                                changed = true
                            }
                        }
                    }
                    is org.objectweb.asm.tree.FieldInsnNode -> {
                        // same CShadow name-only remap, for field stubs:
                        // `Object`-typed shadows emit the stub's desc —
                        // rewrite to the real same-named field's desc
                        if (insn.owner == node.name &&
                            node.fields.none { it.name == insn.name && it.desc == insn.desc }
                        ) {
                            val candidates = node.fields.filter { it.name == insn.name }
                            if (candidates.size == 1) {
                                insn.desc = candidates[0].desc
                                changed = true
                            }
                        }
                    }
                }
            }
        }
        if (!changed) return bytes
        val writer = org.objectweb.asm.ClassWriter(0)
        node.accept(writer)
        return writer.toByteArray()
    }

    /** Copy a compiled class from the plugin classpath into the jar. */
    fun splice(fs: FileSystem, entry: String) {
        val target = fs.getPath(entry)
        ClassTransformJarFix::class.java.classLoader.getResourceAsStream(entry)?.use {
            target.parent?.let { p -> java.nio.file.Files.createDirectories(p) }
            java.nio.file.Files.copy(it, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        } ?: throw IOException("helper class $entry not on plugin classpath")
    }
}

/** fallback provider: stub ClassNode for classes the jar + runtime
    classpath can't resolve (e.g. MC classes outside the jar) */
private class LenientClassProvider(private val next: net.lenni0451.classtransform.utils.tree.IClassProvider) :
    net.lenni0451.classtransform.utils.tree.IClassProvider {
    override fun getClass(name: String): ByteArray {
        try {
            next.getClass(name)?.let { return it }
        } catch (e: Throwable) { }
        // minimal stub: public class <name> extends Object {}
        val cw = org.objectweb.asm.ClassWriter(0)
        cw.visit(org.objectweb.asm.Opcodes.V1_8, org.objectweb.asm.Opcodes.ACC_PUBLIC,
            name, null, "java/lang/Object", null)
        cw.visitEnd()
        return cw.toByteArray()
    }

    override fun getAllClasses() = next.allClasses
}
