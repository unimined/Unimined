package xyz.wagyourtail.unimined.internal.mapping.extension.jma.refmap.annotations.field

import net.fabricmc.tinyremapper.extension.mixin.common.ResolveUtility
import net.fabricmc.tinyremapper.extension.mixin.common.data.AnnotationElement
import net.fabricmc.tinyremapper.extension.mixin.common.data.Constant
import org.objectweb.asm.AnnotationVisitor
import xyz.wagyourtail.unimined.internal.mapping.extension.jma.dontRemap
import xyz.wagyourtail.unimined.internal.mapping.extension.mixin.refmap.RefmapBuilderClassVisitor
import xyz.wagyourtail.unimined.internal.mapping.extension.splitFieldNameAndDescriptor
import xyz.wagyourtail.unimined.util.orElseOptional
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean

abstract class CAbstractFieldAnnotationVisitor(
    descriptor: String,
    visible: Boolean,
    parent: AnnotationVisitor,
    fieldAccess: Int,
    protected val fieldName: String,
    protected val fieldDescriptor: String,
    protected val fieldSignature: String?,
    fieldValue: Any?,
    protected val refmapBuilder: RefmapBuilderClassVisitor
) : AnnotationVisitor(Constant.ASM_VERSION, parent) {

    abstract val annotationName: String

    protected val remap = AtomicBoolean(!refmapBuilder.dontRemap(descriptor))
    protected var targetNames = mutableListOf<String>()

    protected val resolver = refmapBuilder.resolver
    protected val logger = refmapBuilder.logger
    protected val existingMappings = refmapBuilder.existingMappings
    protected val mapper = refmapBuilder.mapper
    protected val refmap = refmapBuilder.refmap
    protected val mixinName = refmapBuilder.mixinName
    protected val targetClasses = refmapBuilder.targetClasses
    protected val allowImplicitWildcards = refmapBuilder.allowImplicitWildcards
    protected val noRefmap = refmapBuilder.mixinRemapExtension.noRefmap.contains("JarModAgent")

    override fun visit(name: String?, value: Any) {
        super.visit(name, value)
        if (name == AnnotationElement.REMAP) remap.set(value as Boolean)
    }

    open fun getTargetNameAndDescs(targetField: String): Pair<String, Set<String?>> {
        val targetDescs = setOf(if (targetField.contains(":")) {
            targetField.substringAfter(":")
        } else {
            null
        })
        val targetName = targetField.substringBefore(":")
        return targetName to targetDescs
    }

    open fun remapTargetNames(noRefmapAcceptor: (String) -> Unit) {
        if (remap.get()) {
            outer@for (targetField in targetNames) {
                val (targetName, targetDescs) = getTargetNameAndDescs(targetField)
                for (targetDesc in targetDescs) {
                    var implicitWildcard = targetDesc == null && allowImplicitWildcards
                    for (targetClass in targetClasses) {
                        val target = resolver.resolveField(
                            targetClass,
                            targetName,
                            targetDesc,
                            (if (implicitWildcard) ResolveUtility.FLAG_FIRST else ResolveUtility.FLAG_UNIQUE) or ResolveUtility.FLAG_RECURSIVE
                        )
                        .orElseOptional {
                            existingMappings[targetField]?.let { existing ->
                                logger.info("Remapping using existing mapping for $targetField: $existing")
                                val (fName, fDesc) = splitFieldNameAndDescriptor(existing)
                                if (fDesc == null && allowImplicitWildcards) {
                                    implicitWildcard = true
                                }
                                resolver.resolveField(
                                    targetClass,
                                    fName,
                                    fDesc,
                                    (if (implicitWildcard) ResolveUtility.FLAG_FIRST else ResolveUtility.FLAG_UNIQUE) or ResolveUtility.FLAG_RECURSIVE
                                )
                            } ?: Optional.empty()
                        }
                        target.ifPresent { targetVal ->
                            val mappedClass = resolver.resolveClass(targetClass)
                                .map { mapper.mapName(it) }
                                .orElse(targetClass)
                            val mappedName = mapper.mapName(targetVal)
                            val mappedDesc = mapper.mapDesc(targetVal)
                            if (targetClasses.size > 1) {
                                refmap.addProperty(targetField, "$mappedName$mappedDesc")
                                noRefmapAcceptor("$mappedName$mappedDesc")
                            } else {
                                refmap.addProperty(targetField, "L$mappedClass;$mappedName:$mappedDesc")
                                noRefmapAcceptor("L$mappedClass;$mappedName:$mappedDesc")
                            }
                        }

                        if (target.isPresent) {
                            continue@outer
                        }
                    }
                }
                logger.warn(
                    "Failed to resolve $annotationName $targetField ($targetDescs) on ($fieldName:$fieldDescriptor) $fieldSignature in $mixinName"
                )
                noRefmapAcceptor(targetField)
            }
        } else {
            for (targetField in targetNames) {
                noRefmapAcceptor(targetField)
            }
        }
    }

}