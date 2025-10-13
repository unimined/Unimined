package xyz.wagyourtail.unimined.internal.mapping.extension.jma.refmap.annotations.method

import net.fabricmc.tinyremapper.extension.mixin.common.ResolveUtility
import net.fabricmc.tinyremapper.extension.mixin.common.data.AnnotationElement
import net.fabricmc.tinyremapper.extension.mixin.common.data.Constant
import org.objectweb.asm.AnnotationVisitor
import xyz.wagyourtail.unimined.internal.mapping.extension.jma.dontRemap
import xyz.wagyourtail.unimined.internal.mapping.extension.mixin.refmap.RefmapBuilderClassVisitor
import xyz.wagyourtail.unimined.internal.mapping.extension.splitMethodNameAndDescriptor
import xyz.wagyourtail.unimined.util.orElseOptional
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean

abstract class CAbstractMethodAnnotationVisitor(
    descriptor: String,
    visible: Boolean,
    parent: AnnotationVisitor,
    methodAccess: Int,
    protected val methodName: String,
    protected val methodDescriptor: String,
    protected val methodSignature: String?,
    methodExceptions: Array<out String>?,
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

    open fun getTargetNameAndDescs(targetMethod: String, wildcard: Boolean): Pair<String, Set<String?>> {
        val targetDescs = setOf(if (targetMethod.contains("(")) {
            "(" + targetMethod.substringAfter("(")
        } else {
            null
        })
        val targetName = if (wildcard) {
            targetMethod.substringBefore("*")
        } else {
            targetMethod.substringBefore("(")
        }
        return targetName to targetDescs
    }

    open fun remapTargetNames(noRefmapAcceptor: (String) -> Unit) {
        if (remap.get()) {
            outer@for (targetMethod in targetNames) {
                if (targetMethod == "<init>" || targetMethod == "<clinit>" ||
                    targetMethod == "<init>*"
                ) {
                    noRefmapAcceptor(targetMethod)
                    continue
                }
                val wildcard = targetMethod.endsWith("*")
                val (targetName, targetDescs) = getTargetNameAndDescs(targetMethod, wildcard)
                for (targetDesc in targetDescs) {
                    var implicitWildcard = targetDesc == null && allowImplicitWildcards
                    for (targetClass in targetClasses) {
                        val target = if (targetDesc != null && targetDesc.endsWith(")")) {
                            resolver.resolveClass(targetClass).map {
                                it.resolveMethods(targetName, targetDesc, true, null, null)
                            }.flatMap {
                                if (it.size > 1 && !(wildcard || implicitWildcard)) throw IllegalStateException("Found multiple methods for $targetMethod ($targetDesc) in $targetClass: $it")
                                Optional.ofNullable(it.firstOrNull())
                            }
                        } else {
                            resolver.resolveMethod(
                                targetClass,
                                targetName,
                                targetDesc,
                                (if (wildcard || implicitWildcard) ResolveUtility.FLAG_FIRST else ResolveUtility.FLAG_UNIQUE) or ResolveUtility.FLAG_RECURSIVE
                            )
                        }
                        .orElseOptional {
                            existingMappings[targetMethod]?.let { existing ->
                                logger.info("Remapping using existing mapping for $targetMethod: $existing")
                                if (wildcard) {
                                    val mname = existing.substringAfter(";").let { it.substring(0, it.length - 1 ) }
                                    resolver.resolveMethod(
                                        targetClass,
                                        mname,
                                        null,
                                        ResolveUtility.FLAG_FIRST or ResolveUtility.FLAG_RECURSIVE
                                    )
                                } else {
                                    val (mName, mDesc) = splitMethodNameAndDescriptor(existing)
                                    if (mDesc == null && allowImplicitWildcards) {
                                        implicitWildcard = true
                                    }
                                    resolver.resolveMethod(
                                        targetClass,
                                        mName,
                                        mDesc,
                                        (if (implicitWildcard) ResolveUtility.FLAG_FIRST else ResolveUtility.FLAG_UNIQUE) or ResolveUtility.FLAG_RECURSIVE
                                    )
                                }
                            } ?: Optional.empty()
                        }
                        target.ifPresent { targetVal ->
                            val mappedClass = resolver.resolveClass(targetClass)
                                .map { mapper.mapName(it) }
                                .orElse(targetClass)
                            val mappedName = mapper.mapName(targetVal)
                            val mappedDesc = /* if (implicitWildcard) "" else */ if (wildcard && mappedName != "<clinit>") "*" else mapper.mapDesc(targetVal)
//                            if (targetClasses.size > 1) {
                                refmap.addProperty(targetMethod, "$mappedName$mappedDesc")
                                noRefmapAcceptor("$mappedName$mappedDesc")
//                            } else {
//                                refmap.addProperty(targetMethod, "L$mappedClass;$mappedName$mappedDesc")
//                                noRefmapAcceptor("L$mappedClass;$mappedName$mappedDesc")
//                            }
                        }

                        if (target.isPresent) {
                            continue@outer
                        }
                    }
                }
                logger.warn(
                    "Failed to resolve $annotationName $targetMethod ($targetDescs) on ($methodName$methodDescriptor) $methodSignature in $mixinName"
                )
                noRefmapAcceptor(targetMethod)
            }
        }
    }

}