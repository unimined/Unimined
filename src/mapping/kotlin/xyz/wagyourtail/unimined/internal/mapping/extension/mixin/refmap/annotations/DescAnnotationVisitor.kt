package xyz.wagyourtail.unimined.internal.mapping.extension.mixin.refmap.annotations

import net.fabricmc.tinyremapper.extension.mixin.common.ResolveUtility
import net.fabricmc.tinyremapper.extension.mixin.common.data.Constant
import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.Type
import xyz.wagyourtail.unimined.internal.mapping.extension.mixin.refmap.RefmapBuilderClassVisitor
import xyz.wagyourtail.unimined.util.orElseOptional
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean

open class DescAnnotationVisitor (parent: AnnotationVisitor?, val remap: AtomicBoolean, val refmapBuilder: RefmapBuilderClassVisitor) : AnnotationVisitor(Constant.ASM_VERSION, parent) {

    var owner: Set<String>? = refmapBuilder.targetClasses
    var value: String? = null
    var ret: Type = Type.VOID_TYPE
    var args: List<Type> = listOf()

    private val refmap = refmapBuilder.refmap
    private val resolver = refmapBuilder.resolver
    private val mapper = refmapBuilder.mapper
    private val logger = refmapBuilder.logger
    private val existingMappings = refmapBuilder.existingMappings
    private val noRefmap = refmapBuilder.mixinRemapExtension.noRefmap.contains("BaseMixin")


    override fun visit(name: String, value: Any) {
        when (name) {
            "owner" -> {
                owner = setOf((value as Type).internalName)
                super.visit(name, value)
            }
            "value" -> {
                value as String
                if (!noRefmap) super.visit(name, value)
            }
            "ret" -> {
                ret = value as Type
                super.visit(name, value)
            }
            else -> {
                super.visit(name, value)
            }
        }
    }

    override fun visitArray(name: String?): AnnotationVisitor {
        return when (name) {
            "args" -> {
                object : AnnotationVisitor(Constant.ASM_VERSION, super.visitArray(name)) {
                    var types = mutableListOf<Type>()

                    override fun visit(name: String?, value: Any) {
                        super.visit(name, value)
                        types.add(value as Type)
                    }

                    override fun visitEnd() {
                        super.visitEnd()
                        args = types
                    }
                }
            }
            else -> super.visitArray(name)
        }
    }

    override fun visitEnd() {
        if (remap.get()) {
            if (owner == null || value == null) {
                logger.warn("Invalid @Desc annotation, owner or value is null")
                if (noRefmap && value != null) {
                    super.visit("value", value)
                }
                super.visitEnd()
                return
            }
            // attempt to resolve method/field
            for (o in owner!!) {

                if (ret != Type.VOID_TYPE) {
                    // match field
                    val field = resolver.resolveField(
                        o.replace(".", "/"),
                        value!!,
                        ret.descriptor,
                        ResolveUtility.FLAG_UNIQUE or ResolveUtility.FLAG_RECURSIVE
                    ).orElseOptional {
                        existingMappings[value]?.let { existing ->
                            resolver.resolveField(
                                o.replace(".", "/"),
                                existing,
                                ret.descriptor,
                                ResolveUtility.FLAG_UNIQUE or ResolveUtility.FLAG_RECURSIVE
                            )
                        } ?: Optional.empty()
                    }

                    if (field.isPresent) {
                        val f = field.get()
                        val fName = mapper.mapName(f)
                        refmap.addProperty(value, fName)
                        if (noRefmap) {
                            super.visit("value", fName)
                        }
                        super.visitEnd()
                        return
                    }
                }

                // match method
                val method = resolver.resolveMethod(
                    o.replace(".", "/"),
                    value!!,
                    Type.getMethodDescriptor(ret, *args.toTypedArray()),
                    ResolveUtility.FLAG_UNIQUE or ResolveUtility.FLAG_RECURSIVE
                ).orElseOptional {
                    existingMappings[value]?.let { existing ->
                        resolver.resolveMethod(
                            o.replace(".", "/"),
                            existing,
                            Type.getMethodDescriptor(ret, *args.toTypedArray()),
                            ResolveUtility.FLAG_UNIQUE or ResolveUtility.FLAG_RECURSIVE
                        )
                    } ?: Optional.empty()
                }

                if (method.isPresent) {
                    val m = method.get()
                    val mName = mapper.mapName(m)
                    refmap.addProperty(value, mName)
                    if (noRefmap) {
                        super.visit("value", mName)
                    }
                    super.visitEnd()
                    return
                }
            }

            logger.warn("Failed to resolve @Desc target \"$value\" in mixin ${refmapBuilder.mixinName.replace('/', '.')}")
            if (noRefmap) {
                super.visit("value", value)
            }
            super.visitEnd()
        }
    }

}
