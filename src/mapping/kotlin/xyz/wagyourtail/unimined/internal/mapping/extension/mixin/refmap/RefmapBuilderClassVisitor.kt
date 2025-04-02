package xyz.wagyourtail.unimined.internal.mapping.extension.mixin.refmap

import com.google.gson.JsonObject
import net.fabricmc.tinyremapper.extension.mixin.common.data.CommonData
import net.fabricmc.tinyremapper.extension.mixin.common.data.Constant
import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.MethodVisitor
import xyz.wagyourtail.unimined.internal.mapping.extension.MixinRemapExtension
import java.util.concurrent.atomic.AtomicBoolean

typealias ClassAnnotationPredicate = (
        descriptor: String,
        visible: Boolean,
        refmapBuilder: RefmapBuilderClassVisitor
    ) -> Boolean
typealias ClassAnnotationVisitor = (
        descriptor: String,
        visible: Boolean,
        parent: AnnotationVisitor,
        refmapBuilder: RefmapBuilderClassVisitor
    ) -> AnnotationVisitor

typealias MethodAnnotationPredicate = (
        descriptor: String,
        visible: Boolean,
        methodAccess: Int,
        methodName: String,
        methodDescriptor: String,
        methodSignature: String?,
        methodExceptions: Array<out String>?,
        refmapBuilder: RefmapBuilderClassVisitor
    ) -> Boolean
typealias MethodAnnotationVisitor = (
        descriptor: String,
        visible: Boolean,
        parent: AnnotationVisitor,
        methodAccess: Int,
        methodName: String,
        methodDescriptor: String,
        methodSignature: String?,
        methodExceptions: Array<out String>?,
        refmapBuilder: RefmapBuilderClassVisitor
    ) -> AnnotationVisitor

typealias FieldAnnotationPredicate = (
        descriptor: String,
        visible: Boolean,
        access: Int,
        fieldName: String,
        fieldDescriptor: String,
        fieldSignature: String?,
        fieldValue: Any?,
        refmapBuilder: RefmapBuilderClassVisitor
    ) -> Boolean
typealias FieldAnnotationVisitor = (
        descriptor: String,
        visible: Boolean,
        parent: AnnotationVisitor,
        fieldAccess: Int,
        fieldName: String,
        fieldDescriptor: String,
        fieldSignature: String?,
        fieldValue: Any?,
        refmapBuilder: RefmapBuilderClassVisitor
    ) -> AnnotationVisitor

class RefmapBuilderClassVisitor(
    commonData: CommonData,
    val mixinName: String,
    val refmap: JsonObject,
    delegate: ClassVisitor,
    val existingMappings: Map<String, String>,
    val mixinRemapExtension: MixinRemapExtension,
    private val onEnd: () -> Unit = {},
    val allowImplicitWildcards: Boolean = false
) : ClassVisitor(Constant.ASM_VERSION, delegate) {

    val mapper = commonData.mapper
    val resolver = commonData.resolver
    val logger = commonData.logger

    val classAnnotationVisitors: MutableList<Pair<ClassAnnotationPredicate, ClassAnnotationVisitor>> = mutableListOf()

    val fieldAnnotationVisitors: MutableList<Pair<FieldAnnotationPredicate, FieldAnnotationVisitor>> = mutableListOf()

    val methodAnnotationVisitors: MutableList<Pair<MethodAnnotationPredicate, MethodAnnotationVisitor>> = mutableListOf()

    val targetClasses = mutableSetOf<String>()
    val remap = AtomicBoolean(true)

    val extraData: MutableMap<String, Any> = mutableMapOf()

    fun insertVisitor(visitor: (ClassVisitor) -> ClassVisitor) {
        val old = this.cv
        this.cv = visitor(old)
    }

    override fun visitAnnotation(descriptor: String?, visible: Boolean): AnnotationVisitor {
        if (descriptor != null) {
            for ((predicate, visitor) in classAnnotationVisitors) {
                if (predicate(descriptor, visible, this)) {
                    return visitor(descriptor, visible, super.visitAnnotation(descriptor, visible), this)
                }
            }
        }
        return super.visitAnnotation(descriptor, visible)
    }

    override fun visitMethod(
        access: Int,
        methodName: String,
        methodDescriptor: String,
        signature: String?,
        exceptions: Array<out String>?
    ): MethodVisitor {
        return object : MethodVisitor(Constant.ASM_VERSION, super.visitMethod(access, methodName, methodDescriptor, signature, exceptions)) {
            override fun visitAnnotation(descriptor: String?, visible: Boolean): AnnotationVisitor {
                if (descriptor != null) {
                    for ((predicate, visitor) in methodAnnotationVisitors) {
                        if (predicate(descriptor, visible, access, methodName, methodDescriptor, signature, exceptions, this@RefmapBuilderClassVisitor)) {
                            return visitor(descriptor, visible, super.visitAnnotation(descriptor, visible), access, methodName, methodDescriptor, signature, exceptions, this@RefmapBuilderClassVisitor)
                        }
                    }
                }
                return super.visitAnnotation(descriptor, visible)
            }
        }
    }

    override fun visitField(
        access: Int,
        fieldName: String,
        fieldDescriptor: String,
        signature: String?,
        value: Any?
    ): FieldVisitor {
        return object : FieldVisitor(Constant.ASM_VERSION, super.visitField(access, fieldName, fieldDescriptor, signature, value)) {
            override fun visitAnnotation(descriptor: String?, visible: Boolean): AnnotationVisitor {
                if (descriptor != null) {
                    for ((predicate, visitor) in fieldAnnotationVisitors) {
                        if (predicate(descriptor, visible, access, fieldName, fieldDescriptor, signature, value, this@RefmapBuilderClassVisitor)) {
                            return visitor(descriptor, visible, super.visitAnnotation(descriptor, visible), access, fieldName, fieldDescriptor, signature, value, this@RefmapBuilderClassVisitor)
                        }
                    }
                }
                return super.visitAnnotation(descriptor, visible)
            }
        }
    }

    override fun visitEnd() {
        super.visitEnd()
        onEnd()
    }


}
