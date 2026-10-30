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
                manager.transform(name, path.readBytes())?.let(path::writeBytes)
            }
        }

        for (helper in helpers) {
            splice(fs, helper)
        }
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
