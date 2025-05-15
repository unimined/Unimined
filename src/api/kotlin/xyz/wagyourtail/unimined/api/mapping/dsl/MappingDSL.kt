package xyz.wagyourtail.unimined.api.mapping.dsl

import groovy.lang.Closure
import groovy.lang.DelegatesTo
import xyz.wagyourtail.commonskt.utils.filterNotNullValues
import xyz.wagyourtail.unimined.mapping.Namespace
import xyz.wagyourtail.unimined.mapping.jvms.four.three.three.MethodDescriptor
import xyz.wagyourtail.unimined.mapping.jvms.four.three.two.FieldDescriptor
import xyz.wagyourtail.unimined.mapping.jvms.four.two.one.InternalName
import xyz.wagyourtail.unimined.mapping.util.Scoped
import xyz.wagyourtail.unimined.mapping.visitor.ClassVisitor
import xyz.wagyourtail.unimined.mapping.visitor.FieldVisitor
import xyz.wagyourtail.unimined.mapping.visitor.MappingVisitor
import xyz.wagyourtail.unimined.mapping.visitor.MethodVisitor
import xyz.wagyourtail.unimined.mapping.visitor.use

@Scoped
class MappingDSL(val visitor: MappingVisitor) {

    var mappings = listOf<Namespace>()

    fun namespace(vararg namespaces: String) {
        mappings = namespaces.map { Namespace(it) }.toList()
    }

    @JvmOverloads
    fun c(vararg names: String?, block: ClassDSL.() -> Unit = {}) {
        visitor.visitClass(mappings.zip(names.toList()).toMap().filterNotNullValues().mapValues { InternalName.read(it.value) })?.use {
            this@MappingDSL.ClassDSL(this).block()
        }
    }

    @JvmOverloads
    fun c(
        names: List<String?>,
        @DelegatesTo(ClassDSL::class, strategy = Closure.DELEGATE_FIRST)
        block: Closure<*>? = null
    ) {
        c(*names.toTypedArray()) {
            if (block != null) {
                block.delegate = this
                block.call()
            }
        }
    }

    @Scoped
    inner class ClassDSL(val visitor: ClassVisitor) {

        @JvmOverloads
        fun f(vararg names: String?, block: FieldDSL.() -> Unit = {}) {
            visitor.visitField(this@MappingDSL.mappings.zip(names.toList()).toMap().filterNotNullValues().mapValues {
                if (it.value.contains(";")) {
                    val split = it.value.split(";", limit = 2)
                    split[0] to FieldDescriptor.read(split[1])
                } else {
                    it.value to null
                }
            })?.use {
                this@MappingDSL.FieldDSL(this).block()
            }
        }

        @JvmOverloads
        fun f(
            names: List<String?>,
            @DelegatesTo(FieldDSL::class, strategy = Closure.DELEGATE_FIRST)
            block: Closure<*>? = null
        ) {
            f(*names.toTypedArray()) {
                if (block != null) {
                    block.delegate = this
                    block.call()
                }
            }
        }

        @JvmOverloads
        fun m(vararg names: String?, block: MethodDSL.() -> Unit = {}) {
            visitor.visitMethod(this@MappingDSL.mappings.zip(names.toList()).toMap().filterNotNullValues().mapValues {
                if (it.value.contains(";")) {
                    val split = it.value.split(";", limit = 2)
                    split[0] to MethodDescriptor.read(split[1])
                } else {
                    it.value to null
                }
            })?.use {
                this@MappingDSL.MethodDSL(this).block()
            }
        }

        @JvmOverloads
        fun m(
            names: List<String?>,
            @DelegatesTo(MethodDSL::class, strategy = Closure.DELEGATE_FIRST)
            block: Closure<*>? = null
        ) {
            m(*names.toTypedArray()) {
                if (block != null) {
                    block.delegate = this
                    block.call()
                }
            }
        }

    }

    @Scoped
    inner class FieldDSL(val visitor: FieldVisitor) {

    }

    @Scoped
    inner class MethodDSL(val visitor: MethodVisitor) {

    }
}