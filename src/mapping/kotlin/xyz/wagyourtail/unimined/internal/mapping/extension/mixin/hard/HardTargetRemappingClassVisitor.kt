package xyz.wagyourtail.unimined.internal.mapping.extension.mixin.hard

import net.fabricmc.tinyremapper.extension.mixin.common.Logger
import net.fabricmc.tinyremapper.extension.mixin.common.data.CommonData
import net.fabricmc.tinyremapper.extension.mixin.common.data.Constant
import net.fabricmc.tinyremapper.extension.mixin.common.data.MxClass
import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.MethodVisitor
import java.util.*
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicBoolean

typealias ClassAnnotationPredicate = (
    descriptor: String,
    visible: Boolean,
    hardTargetRemapper: HardTargetRemappingClassVisitor
) -> Boolean
typealias ClassAnnotationVisitor = (
    descriptor: String,
    visible: Boolean,
    parent: AnnotationVisitor?,
    hardTargetRemapper: HardTargetRemappingClassVisitor
) -> AnnotationVisitor

typealias MethodAnnotationPredicate = (
    descriptor: String,
    visible: Boolean,
    methodAccess: Int,
    methodName: String,
    methodDescriptor: String,
    methodSignature: String?,
    methodExceptions: Array<out String>?,
    hardTargetRemapper: HardTargetRemappingClassVisitor
) -> Boolean
typealias MethodAnnotationVisitor = (
    descriptor: String,
    visible: Boolean,
    parent: AnnotationVisitor?,
    methodAccess: Int,
    methodName: String,
    methodDescriptor: String,
    methodSignature: String?,
    methodExceptions: Array<out String>?,
    hardTargetRemapper: HardTargetRemappingClassVisitor
) -> AnnotationVisitor

typealias FieldAnnotationPredicate = (
    descriptor: String,
    visible: Boolean,
    access: Int,
    fieldName: String,
    fieldDescriptor: String,
    fieldSignature: String?,
    fieldValue: Any?,
    hardTargetRemapper: HardTargetRemappingClassVisitor
) -> Boolean
typealias FieldAnnotationVisitor = (
    descriptor: String,
    visible: Boolean,
    parent: AnnotationVisitor?,
    fieldAccess: Int,
    fieldName: String,
    fieldDescriptor: String,
    fieldSignature: String?,
    fieldValue: Any?,
    hardTargetRemapper: HardTargetRemappingClassVisitor
) -> AnnotationVisitor

class HardTargetRemappingClassVisitor(
    delegate: ClassVisitor?,
    val mixinName: String,
    val existingMappings: Map<String, String>,
    val logger: Logger,
    val onEnd: () -> Unit = {}
) : ClassVisitor(Constant.ASM_VERSION, delegate) {

    lateinit var mxClass: MxClass

    val tasks: Deque<(CommonData) -> Unit> = ConcurrentLinkedDeque()
    val targetClasses = mutableSetOf<String>()
    val remap = AtomicBoolean(true)
    val extraData: MutableMap<String, Any> = mutableMapOf()


    val classAnnotationVisitors: MutableList<Pair<ClassAnnotationPredicate, ClassAnnotationVisitor>> = mutableListOf()

    val methodAnnotationVisitors: MutableList<Pair<MethodAnnotationPredicate, MethodAnnotationVisitor>> = mutableListOf()

    val fieldAnnotationVisitors: MutableList<Pair<FieldAnnotationPredicate, FieldAnnotationVisitor>> = mutableListOf()

    fun insertVisitor(visitor: (ClassVisitor) -> ClassVisitor) {
        val old = this.cv
        this.cv = visitor(old)
    }

    fun addRemapTask(task: CommonData.() -> Unit) {
        tasks.addLast(task)
    }

    /**
     * should only really be used by tasks that mutate {@link #targetClasses}
     */
    fun addRemapTaskFirst(task: CommonData.() -> Unit) {
        tasks.addFirst(task)
    }

    override fun visit(
        version: Int,
        access: Int,
        name: String,
        signature: String?,
        superName: String,
        interfaces: Array<out String>?
    ) {
        mxClass = MxClass(name)
        super.visit(version, access, name, signature, superName, interfaces)
    }

    override fun visitAnnotation(descriptor: String?, visible: Boolean): AnnotationVisitor? {
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
            override fun visitAnnotation(descriptor: String?, visible: Boolean): AnnotationVisitor? {
                if (descriptor != null) {
                    for ((predicate, visitor) in methodAnnotationVisitors) {
                        if (predicate(descriptor, visible, access, methodName, methodDescriptor, signature, exceptions, this@HardTargetRemappingClassVisitor)) {
                            return visitor(descriptor, visible, super.visitAnnotation(descriptor, visible), access, methodName, methodDescriptor, signature, exceptions, this@HardTargetRemappingClassVisitor)
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
            override fun visitAnnotation(descriptor: String?, visible: Boolean): AnnotationVisitor? {
                if (descriptor != null) {
                    for ((predicate, visitor) in fieldAnnotationVisitors) {
                        if (predicate(descriptor, visible, access, fieldName, fieldDescriptor, signature, value, this@HardTargetRemappingClassVisitor)) {
                            return visitor(descriptor, visible, super.visitAnnotation(descriptor, visible), access, fieldName, fieldDescriptor, signature, value, this@HardTargetRemappingClassVisitor)
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

    fun runRemap(data: CommonData) {
        for (task in tasks) {
            task(data)
        }
    }

}
