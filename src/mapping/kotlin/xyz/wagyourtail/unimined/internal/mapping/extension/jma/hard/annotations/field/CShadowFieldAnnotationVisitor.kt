import net.fabricmc.tinyremapper.extension.mixin.common.ResolveUtility
import net.fabricmc.tinyremapper.extension.mixin.common.data.AnnotationElement
import net.fabricmc.tinyremapper.extension.mixin.common.data.Constant
import org.objectweb.asm.AnnotationVisitor
import xyz.wagyourtail.unimined.internal.mapping.extension.jma.JarModAgent
import xyz.wagyourtail.unimined.internal.mapping.extension.jma.dontRemap
import xyz.wagyourtail.unimined.internal.mapping.extension.mixin.hard.HardTargetRemappingClassVisitor

class CShadowFieldAnnotationVisitor(
    descriptor: String,
    visible: Boolean,
    parent: AnnotationVisitor?,
    fieldAccess: Int,
    protected val fieldName: String,
    protected val fieldDescriptor: String,
    fieldSignature: String?,
    fieldValue: Any?,
    protected val hardTargetRemapper: HardTargetRemappingClassVisitor,
)  : AnnotationVisitor(
    Constant.ASM_VERSION,
    parent
) {

    protected val remap = !hardTargetRemapper.dontRemap(descriptor)
    protected val logger = hardTargetRemapper.logger
    protected val existingMappings = hardTargetRemapper.existingMappings
    protected val targetClasses = hardTargetRemapper.targetClasses
    protected val mixinName = hardTargetRemapper.mixinName

    companion object {

        fun shouldVisit(
            descriptor: String,
            visible: Boolean,
            access: Int,
            fieldName: String,
            fieldDescriptor: String,
            fieldSignature: String?,
            fieldValue: Any?,
            hardTargetRemapper: HardTargetRemappingClassVisitor
        ): Boolean {
            return descriptor == JarModAgent.Annotation.CSHADOW
        }

    }

    var name: String? = null

    override fun visit(name: String?, value: Any?) {
        if (name == AnnotationElement.VALUE) {
            this.name = value as String
        }
        super.visit(name, value)
    }

    override fun visitEnd() {
        super.visitEnd()
        hardTargetRemapper.addRemapTask {
            if (remap && name == null) {
                for (target in targetClasses) {
                    val resolved = resolver.resolveField(target, fieldName, fieldDescriptor, ResolveUtility.FLAG_UNIQUE or ResolveUtility.FLAG_RECURSIVE)
                    resolved.ifPresent {
                        val mappedName = mapper.mapName(resolved.get())
                        propagate(hardTargetRemapper.mxClass.getField(fieldName, fieldDescriptor).asTrMember(resolver), mappedName)
                    }
                    if (resolved.isPresent) {
                        return@addRemapTask
                    }
                }
                logger.warn("Could not find target field for @Shadow $fieldName:$fieldDescriptor in $mixinName")
            }
        }
    }

}