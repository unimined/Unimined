package xyz.wagyourtail.unimined.internal.mapping.extension.jma.refmap.annotations.clazz

import net.fabricmc.tinyremapper.extension.mixin.common.data.AnnotationElement
import net.fabricmc.tinyremapper.extension.mixin.common.data.Constant
import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.Type
import xyz.wagyourtail.unimined.internal.mapping.extension.jma.JarModAgent
import xyz.wagyourtail.unimined.internal.mapping.extension.jma.dontRemap
import xyz.wagyourtail.unimined.internal.mapping.extension.mixin.refmap.RefmapBuilderClassVisitor
import xyz.wagyourtail.unimined.util.orElseOptional
import java.util.*

@Suppress("UNUSED_PARAMETER")
class CTransformerAnnotationVisitor(
    descriptor: String,
    visible: Boolean,
    parent: AnnotationVisitor,
    refmapBuilder: RefmapBuilderClassVisitor
) : AnnotationVisitor(Constant.ASM_VERSION, parent) {

    companion object {

        fun shouldVisit(descriptor: String, visible: Boolean, refmapBuilder: RefmapBuilderClassVisitor): Boolean {
            return descriptor == JarModAgent.Annotation.CTRANSFORMER
        }

    }

    private val remap = !refmapBuilder.dontRemap(descriptor)
    private val resolver = refmapBuilder.resolver
    private val logger = refmapBuilder.logger
    private val existingMappings = refmapBuilder.existingMappings
    private val mapper = refmapBuilder.mapper
    private val refmap = refmapBuilder.refmap
    private val mixinName = refmapBuilder.mixinName
    private val targetClasses = refmapBuilder.targetClasses
    private val noRefmap = refmapBuilder.mixinRemapExtension.noRefmap.contains("JarModAgent")

    private val classTargets = mutableListOf<String>()
    private val classValues = mutableListOf<String>()

    override fun visit(name: String, value: Any) {
        super.visit(name, value)
        logger.info("Found annotation value $name: $value")
    }

    override fun visitArray(name: String?): AnnotationVisitor {
        return when (name) {
            JarModAgent.AnnotationElement.NAME -> {
                return object: AnnotationVisitor(Constant.ASM_VERSION, if (noRefmap) null else super.visitArray(name)) {
                    override fun visit(name: String?, value: Any) {
                        classTargets.add(value as String)
                        super.visit(name, value)
                    }
                }
            }

            AnnotationElement.VALUE, null -> {
                return object: AnnotationVisitor(Constant.ASM_VERSION, super.visitArray(name)) {
                    override fun visit(name: String?, value: Any) {
                        classValues.add((value as Type).internalName)
                        super.visit(name, value)
                    }
                }
            }

            else -> {
                super.visitArray(name)
            }
        }
    }

    override fun visitEnd() {
        val targets = if (noRefmap && classTargets.isNotEmpty()) {
            super.visitArray(AnnotationElement.TARGETS)
        } else {
            null
        }
        if (remap) {
            logger.info("existing mappings: $existingMappings")
            for (target in classTargets.toSet()) {
                val clz = resolver.resolveClass(target.replace('.', '/'))
                    .orElseOptional {
                        existingMappings[target]?.let {
                            logger.info("remapping $it from existing refmap")
                            classTargets.remove(target)
                            classTargets.add(it)
                            resolver.resolveClass(it)
                        } ?: Optional.empty()
                    }
                clz.ifPresent {
                    refmap.addProperty(target, mapper.mapName(it))
                    targets?.visit(null, target)
                }
                if (!clz.isPresent) {
                    targets?.visit(null, target)
                    logger.warn("Failed to resolve class $target in mixin ${mixinName.replace('/', '.')}")
                }
            }
        } else {
            for (target in classTargets) {
                targets?.visit(null, target)
            }
        }
        targets?.visitEnd()
        targetClasses.addAll((classValues + classTargets.map { it.replace('.', '/') }).toSet())
        super.visitEnd()
    }
}
