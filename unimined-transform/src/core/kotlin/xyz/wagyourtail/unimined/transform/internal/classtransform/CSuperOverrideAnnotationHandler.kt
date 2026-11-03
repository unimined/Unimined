package xyz.wagyourtail.unimined.transform.internal.classtransform

import net.lenni0451.classtransform.TransformerManager
import net.lenni0451.classtransform.transformer.AnnotationHandler
import org.objectweb.asm.ClassReader
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodNode

/**
 * Handles [CSuperOverride] methods: resolves the target's superclass
 * through the manager's class provider, discovers the member name for
 * the annotation's descriptor, and copies the transformer method into
 * the transformed class under that name (replacing any existing
 * same-named member). Runs at [net.lenni0451.classtransform.transformer.HandlerPosition.PRE]
 * so handled methods are removed from the transformer before the
 * leftover-member copy.
 */
class CSuperOverrideAnnotationHandler : AnnotationHandler() {

    private val annotationDesc = Type.getDescriptor(CSuperOverride::class.java)

    override fun transform(
        transformerManager: TransformerManager,
        transformedClass: ClassNode,
        transformer: ClassNode
    ) {
        val iter = transformer.methods.iterator()
        while (iter.hasNext()) {
            val method = iter.next()
            val annotation = getAnnotation(CSuperOverride::class.java, method, transformerManager)
                ?: continue
            // handled here — never copied verbatim by MemberCopy
            iter.remove()
            val desc = annotation.value
            if (annotation.ifAbsent && transformedClass.methods.any { it.desc == desc }) continue
            val superName = superMethod(transformerManager, transformedClass, desc) ?: continue
            val copy = MethodNode(
                Opcodes.ASM9, method.access, superName, method.desc,
                method.signature, method.exceptions?.toTypedArray()
            )
            method.accept(copy)
            copy.visibleAnnotations?.removeIf { it.desc == annotationDesc }
            copy.invisibleAnnotations?.removeIf { it.desc == annotationDesc }
            transformedClass.methods.removeIf { it.name == superName && it.desc == desc }
            transformedClass.methods.add(copy)
        }
    }

    private fun superMethod(
        transformerManager: TransformerManager,
        transformedClass: ClassNode, desc: String
    ): String? {
        val superBytes = try {
            transformerManager.classProvider.getClass(transformedClass.superName.replace('/', '.'))
        } catch (e: ClassNotFoundException) {
            null
        } ?: return null
        val superNode = ClassNode()
        ClassReader(superBytes).accept(superNode, ClassReader.SKIP_CODE)
        return superNode.methods.firstOrNull { it.desc == desc }?.name
    }
}
