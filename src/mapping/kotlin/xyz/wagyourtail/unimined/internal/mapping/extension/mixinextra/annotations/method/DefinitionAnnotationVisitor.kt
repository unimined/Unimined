package xyz.wagyourtail.unimined.internal.mapping.extension.mixinextra.annotations.method

import net.fabricmc.tinyremapper.extension.mixin.common.ResolveUtility
import net.fabricmc.tinyremapper.extension.mixin.common.data.AnnotationElement
import net.fabricmc.tinyremapper.extension.mixin.common.data.Constant
import org.objectweb.asm.AnnotationVisitor
import xyz.wagyourtail.unimined.internal.mapping.extension.ArrayVisitorWrapper
import xyz.wagyourtail.unimined.internal.mapping.extension.mixin.refmap.RefmapBuilderClassVisitor
import xyz.wagyourtail.unimined.internal.mapping.extension.mixin.refmap.annotations.AtAnnotationVisitor
import xyz.wagyourtail.unimined.internal.mapping.extension.mixin.refmap.annotations.AtAnnotationVisitor.Companion.matchToParts
import xyz.wagyourtail.unimined.internal.mapping.extension.mixin.refmap.annotations.AtAnnotationVisitor.Companion.targetField
import xyz.wagyourtail.unimined.internal.mapping.extension.mixin.refmap.annotations.AtAnnotationVisitor.Companion.targetMethod
import xyz.wagyourtail.unimined.internal.mapping.extension.mixinextra.MixinExtra
import xyz.wagyourtail.unimined.util.orElseOptional
import java.util.Optional
import java.util.concurrent.atomic.AtomicBoolean

class DefinitionAnnotationVisitor(descriptor: String,
      visible: Boolean,
      parent: AnnotationVisitor,
      methodAccess: Int,
      methodName: String,
      methodDescriptor: String,
      methodSignature: String?,
      methodExceptions: Array<out String>?,
      private val refmapBuilder: RefmapBuilderClassVisitor
)  : AnnotationVisitor(
    Constant.ASM_VERSION,
    parent
) {

    protected val remap = AtomicBoolean(refmapBuilder.remap.get())

    private val resolver = refmapBuilder.resolver
    private val logger = refmapBuilder.logger
    private val existingMappings = refmapBuilder.existingMappings
    private val mapper = refmapBuilder.mapper
    private val refmap = refmapBuilder.refmap
    private val mixinName = refmapBuilder.mixinName
    private val noRefmap = refmapBuilder.mixinRemapExtension.noRefmap.contains("BaseMixin")

    companion object {
        fun shouldVisit(
            descriptor: String,
            visible: Boolean,
            methodAccess: Int,
            methodName: String,
            methodDescriptor: String,
            methodSignature: String?,
            methodExceptions: Array<out String>?,
            refmapBuilder: RefmapBuilderClassVisitor
        ): Boolean {
            return descriptor == MixinExtra.Annotation.DEFINITION
        }
    }

    override fun visit(name: String?, value: Any) {
        when (name) {
            AnnotationElement.REMAP -> {
                super.visit(name, value)
                remap.set(value as Boolean)
            }
            else -> super.visit(name, value)
        }
    }

    val targetMethods = mutableListOf<String>()
    val targetFields = mutableListOf<String>()

    override fun visitArray(name: String): AnnotationVisitor {
        return when (name) {
            AnnotationElement.AT -> {
                ArrayVisitorWrapper(Constant.ASM_VERSION, super.visitArray(name)) { AtAnnotationVisitor(it, remap, refmapBuilder) }
            }
            AnnotationElement.METHOD -> {
                object: AnnotationVisitor(Constant.ASM_VERSION, if (noRefmap) null else super.visitArray(name)) {
                    override fun visit(name: String?, value: Any) {
                        if (!noRefmap) super.visit(name, value)
                        targetMethods.add(value as String)
                    }
                }
            }
            MixinExtra.AnnotationElement.FIELD -> {
                object: AnnotationVisitor(Constant.ASM_VERSION, if (noRefmap) null else super.visitArray(name)) {
                    override fun visit(name: String?, value: Any) {
                        if (!noRefmap) super.visit(name, value)
                        targetFields.add(value as String)
                    }
                }
            }
            else -> {
                super.visitArray(name)
            }
        }
    }

    override fun visitEnd() {
        if (remap.get()) {
            if (targetMethods.isNotEmpty()) {
                val mappedMethods = targetMethods.map { targetMethodName ->
                    val matchMd = targetMethod.matchEntire(targetMethodName)
                    if (matchMd == null) error("failed to match $targetMethodName")
                    var (targetOwner, targetName, targetDesc) = matchToParts(matchMd)
                    val target = resolver.resolveMethod(
                        targetOwner,
                        targetName,
                        targetDesc,
                        ResolveUtility.FLAG_UNIQUE or ResolveUtility.FLAG_RECURSIVE
                    ).orElseOptional {
                        existingMappings[targetMethodName]?.let { existing ->
                            logger.info("remapping $existing from existing refmap")
                            val matchEMd = targetMethod.matchEntire(existing)
                            if (matchEMd != null) {
                                val matchResult = matchToParts(matchEMd)
                                targetOwner = matchResult.first
                                val mName = matchResult.second
                                val mDesc = matchResult.third
                                resolver.resolveMethod(
                                    targetOwner,
                                    mName,
                                    mDesc,
                                    ResolveUtility.FLAG_UNIQUE or ResolveUtility.FLAG_RECURSIVE
                                )
                            } else {
                                Optional.empty()
                            }
                        } ?: Optional.empty()
                    }
                    val targetClass = resolver.resolveClass(targetOwner)
                    if (targetClass.isPresent) {
                        val clz = targetClass.get()
                        if (target.isPresent) {
                            val it = target.get()
                            val mappedOwner = mapper.mapName(clz)
                            val mappedName = mapper.mapName(it)
                            val mappedDesc = mapper.mapDesc(it)
                            val mappedTarget = "L$mappedOwner;$mappedName$mappedDesc"
                            refmap.addProperty(targetMethodName, mappedTarget)
                            return@map mappedTarget
                        }
                    }
                    logger.warn(
                        "Failed to resolve Method target $targetMethodName in mixin ${
                            mixinName.replace(
                                '/',
                                '.'
                            )
                        }"
                    )
                    return@map targetMethodName
                }
                if (noRefmap) {
                    val array = super.visitArray(AnnotationElement.METHOD)
                    for (mapped in mappedMethods) {
                        array.visit(null, mapped)
                    }
                    array.visitEnd()
                }
            }
            if (targetFields.isNotEmpty()) {
                val mappedFields = targetFields.map { targetFieldName ->
                    val matchFd = targetField.matchEntire(targetFieldName)
                    if (matchFd == null) error("failed to match $targetFieldName")
                    var (targetOwner, targetName, targetDesc) = matchToParts(matchFd)
                    val target = resolver.resolveField(
                        targetOwner,
                        targetName,
                        targetDesc,
                        ResolveUtility.FLAG_UNIQUE or ResolveUtility.FLAG_RECURSIVE
                    ).orElseOptional {
                        existingMappings[targetFieldName]?.let { existing ->
                            logger.info("remapping $existing from existing refmap")
                            val matchEFd = targetField.matchEntire(existing)
                            if (matchEFd != null) {
                                val matchResult = matchToParts(matchEFd)
                                targetOwner = matchResult.first
                                val fName = matchResult.second
                                val fDesc = matchResult.third
                                resolver.resolveField(
                                    targetOwner,
                                    fName,
                                    fDesc,
                                    ResolveUtility.FLAG_UNIQUE or ResolveUtility.FLAG_RECURSIVE
                                )
                            } else {
                                Optional.empty()
                            }
                        } ?: Optional.empty()
                    }
                    val targetClass = resolver.resolveClass(targetOwner)
                    if (targetClass.isPresent) {
                        val clz = targetClass.get()
                        if (target.isPresent) {
                            val it = target.get()
                            val mappedOwner = mapper.mapName(clz)
                            val mappedName = mapper.mapName(it)
                            val mappedDesc = mapper.mapDesc(it)
                            val mappedTarget = "L$mappedOwner;$mappedName:$mappedDesc"
                            refmap.addProperty(targetFieldName, mappedTarget)
                            return@map mappedTarget
                        }
                    }
                    logger.warn(
                        "Failed to resolve Field target $targetFieldName in mixin ${
                            mixinName.replace(
                                '/',
                                '.'
                            )
                        }"
                    )
                    return@map targetFieldName
                }
                if (noRefmap) {
                    val array = super.visitArray(MixinExtra.AnnotationElement.FIELD)
                    for (mapped in mappedFields) {
                        array.visit(null, mapped)
                    }
                    array.visitEnd()
                }
            }

        }
    }

}
