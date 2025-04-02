package xyz.wagyourtail.unimined.internal.mapping.extension.jma.refmap.annotations.method

import net.fabricmc.tinyremapper.extension.mixin.common.data.AnnotationElement
import net.fabricmc.tinyremapper.extension.mixin.common.data.Constant
import org.objectweb.asm.AnnotationVisitor
import xyz.wagyourtail.unimined.internal.mapping.extension.jma.JarModAgent
import xyz.wagyourtail.unimined.internal.mapping.extension.mixin.refmap.RefmapBuilderClassVisitor

@Suppress("UNUSED_PARAMETER")
class COverrideAnnotationVisitor(
    descriptor: String,
    visible: Boolean,
    parent: AnnotationVisitor,
    methodAccess: Int,
    methodName: String,
    methodDescriptor: String,
    methodSignature: String?,
    methodExceptions: Array<out String>?,
    refmapBuilder: RefmapBuilderClassVisitor,
)  : CAbstractMethodAnnotationVisitor(
    descriptor,
    visible,
    parent,
    methodAccess,
    methodName,
    methodDescriptor,
    methodSignature,
    methodExceptions,
    refmapBuilder
) {

    override val annotationName: String = "@COverride"

    companion object{
        fun shouldVisit(
            descriptor:String,
            visible:Boolean,
            methodAccess:Int,
            methodName:String,
            methodDescriptor:String,
            methodSignature:String?,
            methodExceptions:Array<out String>?,
            refmapBuilder:RefmapBuilderClassVisitor
        ): Boolean{
            return descriptor == JarModAgent.Annotation.CINJECT
        }
    }

    override fun visitArray(name: String): AnnotationVisitor {
        return when (name) {
            AnnotationElement.VALUE -> {
                object: AnnotationVisitor(Constant.ASM_VERSION, if (noRefmap) null else super.visitArray(name)) {
                    override fun visit(name: String?, value: Any) {
                        super.visit(name, value)
                        targetNames.add(value as String)
                    }
                }
            }

            else -> {
                super.visitArray(name)
            }
        }
    }

    override fun visitEnd() {
        val method = if (noRefmap) {
            super.visitArray(AnnotationElement.VALUE)
        } else {
            null
        }
        remapTargetNames {
            method?.visit(null, it)
        }
        method?.visitEnd()
        super.visitEnd()
    }

    override fun getTargetNameAndDescs(targetMethod: String, wildcard: Boolean): Pair<String, Set<String?>> {
        return if (targetMethod.contains("(")) {
            val n = targetMethod.split("(")
            n[0] to setOf("(${n[1]}")
        } else {
            targetMethod to setOf(methodDescriptor)
        }
    }

}
