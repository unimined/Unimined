package xyz.wagyourtail.unimined.internal.mapping.extension.mixinextra.annotations.method

import net.fabricmc.tinyremapper.extension.mixin.common.data.Constant
import org.objectweb.asm.AnnotationVisitor
import xyz.wagyourtail.unimined.internal.mapping.extension.ArrayVisitorWrapper
import xyz.wagyourtail.unimined.internal.mapping.extension.mixin.refmap.RefmapBuilderClassVisitor
import xyz.wagyourtail.unimined.internal.mapping.extension.mixinextra.MixinExtra

class DefinitionsAnnotationVisitor(
    parent: AnnotationVisitor,
    val methodName: String,
    private val refmapBuilder: RefmapBuilderClassVisitor
)  : AnnotationVisitor(
    Constant.ASM_VERSION,
    parent
) {

    constructor(
        descriptor: String,
        visible: Boolean,
        parent: AnnotationVisitor,
        methodAccess: Int,
        methodName: String,
        methodDescriptor: String,
        methodSignature: String?,
        methodExceptions: Array<out String>?,
        refmapBuilder: RefmapBuilderClassVisitor
    ) : this(
        parent,
        methodName,
        refmapBuilder
    )

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
            return descriptor == MixinExtra.Annotation.DEFINITIONS
        }
    }

    override fun visitArray(name: String?): AnnotationVisitor? {
        return when (name) {
            null, "value" -> {
                ArrayVisitorWrapper(Constant.ASM_VERSION, super.visitArray(name)) {
                    DefinitionAnnotationVisitor(it, refmapBuilder)
                }
            }
            else -> super.visitArray(name)
        }
    }

}