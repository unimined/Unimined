@file:Suppress("unused")
package xyz.wagyourtail.unimined.api.mapping.dsl

import groovy.lang.Closure
import groovy.lang.DelegatesTo
import xyz.wagyourtail.unimined.mapping.Namespace
import xyz.wagyourtail.unimined.mapping.jvms.four.three.three.MethodDescriptor
import xyz.wagyourtail.unimined.mapping.jvms.four.three.two.FieldDescriptor
import xyz.wagyourtail.unimined.mapping.jvms.four.two.one.InternalName
import xyz.wagyourtail.unimined.mapping.util.Scoped
import xyz.wagyourtail.unimined.mapping.visitor.ClassVisitor
import xyz.wagyourtail.unimined.mapping.visitor.MappingVisitor
import xyz.wagyourtail.unimined.mapping.visitor.MethodVisitor
import xyz.wagyourtail.unimined.mapping.visitor.use
import xyz.wagyourtail.unimined.util.associated
import xyz.wagyourtail.unimined.util.nonNullValues

/**
 * A class to represent minecraft mappings.
 * @since 0.1.0
 */
@Scoped
class MemoryMapping(val visitor: MappingVisitor) {
    var srcNamespace = "official"

    /**
     * Add a class mapping.
     * @param srcName The source name of the class.
     * @param targets namespace -> target name.
     * @param action A closure to add mappings to the class.
     * @since 0.1.0
     */
    @JvmOverloads
    fun c(srcName: String, vararg targets: Pair<String, String>, action: ClassMapping.() -> Unit = {}) {
        visitor.visitClass(listOf(*targets, srcNamespace to srcName).associate { Namespace(it.first) to InternalName.read(it.second) })?.use {
            action(ClassMapping(this, this@MemoryMapping.srcNamespace))
        }
    }

    /**
     * Add a class mapping.
     * @param srcName The source name of the class.
     * @param targets namespace -> target name.
     * @param action A closure to add mappings to the class.
     * @since 0.1.0
     */
    @JvmOverloads
    fun c(srcName: String, targets: Map<String, String>, action: ClassMapping.() -> Unit = {}) {
        val map = targets.toMutableMap()
        map[srcNamespace] = srcName
        visitor.visitClass(map.entries.associate { Namespace(it.key) to InternalName.read(it.value) })?.use {
            action(ClassMapping(this, this@MemoryMapping.srcNamespace))
        }
    }

    /**
     * @since 0.5.0
     */
    fun c(srcName: String, targets: Map<String, String>, @DelegatesTo(value = ClassMapping::class, strategy = Closure.DELEGATE_FIRST) action: Closure<*>) {
        c(srcName, targets) {
            action.delegate = this
            action.resolveStrategy = Closure.DELEGATE_FIRST
            action.call()
        }
    }

    /**
     * bind a set of target mappings
     * @param mappings The mappings to bind.
     * @since 0.1.0
     * @see MemoryMappingWithMappings
     */
    @JvmName("withMappingsKt")
    fun withMappings(vararg mappings: String, action: MemoryMappingWithMappings.() -> Unit) {
        srcNamespace = "official"
        action(MemoryMappingWithMappings(this, mappings.toList()))
    }

    /**
     * @since 0.4.10
     */
    @JvmName("withMappingsKt")
    fun withMappings(src: String, mappings: List<String>, action: MemoryMappingWithMappings.() -> Unit) {
        srcNamespace = src
        action(MemoryMappingWithMappings(this, mappings))
    }

    /**
     * bind a set of target mappings
     * @param mappings The mappings to bind.
     * @since 0.1.0
     * @see MemoryMappingWithMappings
     */
    fun withMappings(
        mappings: List<String>,
        @DelegatesTo(value = MemoryMappingWithMappings::class, strategy = Closure.DELEGATE_FIRST) action: Closure<*>
    ) {
        withMappings(*mappings.toTypedArray()) {
            action.delegate = this
            action.resolveStrategy = Closure.DELEGATE_FIRST
            action.call()
        }
    }

    /**
     * @since 0.4.10
     */
    fun withMappings(
        src: String,
        mappings: List<String>,
        @DelegatesTo(value = MemoryMappingWithMappings::class, strategy = Closure.DELEGATE_FIRST) action: Closure<*>
    ) {
        withMappings(src, mappings) {
            action.delegate = this
            action.resolveStrategy = Closure.DELEGATE_FIRST
            action.call()
        }
    }
}

/**
 * A class to represent minecraft mappings.
 * with a bound set of mappings
 * @since 0.1.0
 */
@Scoped
class MemoryMappingWithMappings(val memoryMapping: MemoryMapping, val mappings: List<String>) {

    /**
     * Add a class mapping.
     * @param srcName The source name of the class.
     * @param targets target names (in the order of the mappings passed to [MemoryMappingWithMappings])
     * @since 0.1.0
     */
    fun c(srcName: String, vararg targets: String?) {
        memoryMapping.c(srcName, (mappings zip targets).associated().nonNullValues())
    }

    /**
     * Add a class mapping.
     * @param srcName The source name of the class.
     * @param targets target names (in the order of the mappings passed to [MemoryMappingWithMappings])
     * @param action A closure to add mappings to the class.
     * @since 0.1.0
     */
    fun c(srcName: String, targets: List<String?>, action: ClassMappingWithMappings.() -> Unit) {
        memoryMapping.c(srcName, (mappings zip targets).associated().nonNullValues()) {
            action(ClassMappingWithMappings(this, this@MemoryMappingWithMappings.mappings))
        }
    }

    /**
     * @since 0.5.0
     */
    fun c(srcName: String, targets: List<String?>) {
        memoryMapping.c(srcName, (mappings zip targets).associated().nonNullValues())
    }

    /**
     * @since 0.5.0
     */
    fun c(srcName: String, targets: List<String?>, @DelegatesTo(value = ClassMappingWithMappings::class, strategy = Closure.DELEGATE_FIRST) action: Closure<*>) {
        c(srcName, targets) {
            action.delegate = this
            action.resolveStrategy = Closure.DELEGATE_FIRST
            action.call()
        }
    }

    /**
     * @since 0.5.0
     */

}

/**
 * A class to represent a class mapping.
 * @since 0.1.0
 */
@Scoped
class ClassMapping(val visitor: ClassVisitor, val sourceNamespace: String) {

    /**
     * Add a field mapping.
     * @param srcName The source name of the field.
     * @param srcDesc The source descriptor of the field.
     * @param targets namespace -> target name
     * @since 0.1.0
     */
    fun f(srcName: String, srcDesc: String, vararg targets: Pair<String, String>) {
        val map = mutableMapOf<Namespace, Pair<String, FieldDescriptor?>>()
        for (target in targets) {
            map[Namespace(target.first)] = target.second to null
        }
        map[Namespace(sourceNamespace)] = srcName to FieldDescriptor.read(srcDesc)
        visitor.visitField(map)?.visitEnd()
    }

    /**
     * Add a field mapping.
     * @param srcName The source name of the field.
     * @param srcDesc The source descriptor of the field.
     * @param targets namespace -> target name
     * @since 0.1.0
     */
    fun f(srcName: String, srcDesc: String, targets: Map<String, String>) {
        val map = mutableMapOf<Namespace, Pair<String, FieldDescriptor?>>()
        for ((key, value) in targets) {
            map[Namespace(key)] = value to null
        }
        map[Namespace(sourceNamespace)] = srcName to FieldDescriptor.read(srcDesc)
        visitor.visitField(map)?.visitEnd()
    }

    /**
     * Add a method mapping.
     * @param srcName The source name of the field.
     * @param srcDesc The source descriptor of the field.
     * @param targets namespace -> target name
     * @param action A closure to add mappings to the method.
     * @since 0.1.0
     */
    fun m(
        srcName: String,
        srcDesc: String,
        vararg targets: Pair<String, String>,
        action: MethodMapping.() -> Unit = {}
    ) {
        val map = mutableMapOf<Namespace, Pair<String, MethodDescriptor?>>()
        for (target in targets) {
            map[Namespace(target.first)] = target.second to null
        }
        map[Namespace(sourceNamespace)] = srcName to MethodDescriptor.read(srcDesc)
        visitor.visitMethod(map)?.use {
            action(MethodMapping(this, this@ClassMapping.sourceNamespace))
        }
    }

    /**
     * Add a method mapping.
     * @param srcName The source name of the field.
     * @param srcDesc The source descriptor of the field.
     * @param targets namespace -> target name
     * @param action A closure to add mappings to the method.
     * @since 0.1.0
     */
    @JvmOverloads
    fun m(srcName: String, srcDesc: String, targets: Map<String, String>, action: MethodMapping.() -> Unit = {}) {
        val map = mutableMapOf<Namespace, Pair<String, MethodDescriptor?>>()
        for ((key, value) in targets) {
            map[Namespace(key)] = value to null
        }
        map[Namespace(sourceNamespace)] = srcName to MethodDescriptor.read(srcDesc)
        visitor.visitMethod(map)?.use {
            action(MethodMapping(this, this@ClassMapping.sourceNamespace))
        }
    }

    /**
     * @since 0.5.0
     */
    fun m(srcName: String, srcDesc: String, targets: Map<String, String>, @DelegatesTo(value = MethodMapping::class, strategy = Closure.DELEGATE_FIRST) action: Closure<*>) {
        m(srcName, srcDesc, *targets.toList().toTypedArray()) {
            action.delegate = this
            action.resolveStrategy = Closure.DELEGATE_FIRST
            action.call()
        }
    }

    /**
     * Bind a set of mappings to this class.
     * @param mappings The mappings to bind.
     * @param action A closure to add mappings to the class.
     * @since 0.1.0
     * @see ClassMappingWithMappings
     */
    @JvmName("withMappingsKt")
    fun withMappings(vararg mappings: String, action: ClassMappingWithMappings.() -> Unit) {
        action(ClassMappingWithMappings(this, mappings.toList()))
    }

    /**
     * Bind a set of mappings to this class.
     * @param mappings The mappings to bind.
     * @param action A closure to add mappings to the class.
     * @since 0.1.0
     * @see ClassMappingWithMappings
     */
    fun withMappings(
        mappings: List<String>,
        @DelegatesTo(value = ClassMappingWithMappings::class, strategy = Closure.DELEGATE_FIRST) action: Closure<*>
    ) {
        action.delegate = ClassMappingWithMappings(this, mappings)
        action.resolveStrategy = Closure.DELEGATE_FIRST
        action.call()
    }
}

/**
 * A class to represent a class mapping.
 * with a bound set of mappings
 * @since 0.1.0
 */
@Scoped
class ClassMappingWithMappings(val classMapping: ClassMapping, val mappings: List<String>) {
    /**
     * Add a field mapping.
     * @param srcName The source name of the field.
     * @param srcDesc The source descriptor of the field.
     * @param targets target names (in the order of the mappings passed to [ClassMappingWithMappings])
     * @since 0.1.0
     */
    fun f(srcName: String, srcDesc: String, vararg targets: String?) {
        classMapping.f(srcName, srcDesc, (mappings zip targets).associated().nonNullValues())
    }

    /**
     * @since 0.5.0
     */
    fun f(srcName: String, srcDesc: String, targets: List<String?>) {
        f(srcName, srcDesc, *targets.toTypedArray())
    }

    /**
     * Add a field mapping.
     * @param srcName The source name of the field.
     * @param srcDesc The source descriptor of the field.
     * @param targets target names (in the order of the mappings passed to [ClassMappingWithMappings])
     * @since 0.1.0
     */
    fun m(srcName: String, srcDesc: String, vararg targets: String?) {
        classMapping.m(srcName, srcDesc, (mappings zip targets).associated().nonNullValues())
    }

    /**
     * @since 0.5.0
     */
    fun m(srcName: String, srcDesc: String, targets: List<String?>) {
        m(srcName, srcDesc, *targets.toTypedArray())
    }

    /**
     * Add a method mapping.
     * @param srcName The source name of the field.
     * @param srcDesc The source descriptor of the field.
     * @param targets target names (in the order of the mappings passed to [ClassMappingWithMappings])
     * @param action A closure to add mappings to the method.
     * @since 0.1.0
     */
    fun m(srcName: String, srcDesc: String, vararg targets: String?, action: MethodMappingWithMappings.() -> Unit) {
        classMapping.m(srcName, srcDesc, (mappings zip targets).associated().nonNullValues()) {
            action(MethodMappingWithMappings(this, this@ClassMappingWithMappings.mappings))
        }
    }

    /**
     * @since 0.5.0
     */
    fun m(srcName: String, srcDesc: String, targets: List<String?>, @DelegatesTo(value = MethodMappingWithMappings::class, strategy = Closure.DELEGATE_FIRST) action: Closure<*>) {
        m(srcName, srcDesc, *targets.toTypedArray()) {
            action.delegate = this
            action.resolveStrategy = Closure.DELEGATE_FIRST
            action.call()
        }
    }
}


/**
 * A class to represent a method mapping.
 * @since 0.1.0
 */
@Scoped
class MethodMapping(val visitor: MethodVisitor, val sourceNamespace: String) {

    /**
     * Add a parameter mapping.
     * @param index The index of the parameter.
     * @param targets namespace -> target name
     * @since 0.1.0
     */
    fun p(index: Int, vararg targets: Pair<String, String>) {
        visitor.visitParameter(index, null, targets.associate { Namespace(it.first) to it.second })?.visitEnd()
    }

    /**
     * Add a parameter mapping.
     * @param index The index of the parameter.
     * @param targets namespace -> target name
     * @since 0.1.0
     */
    fun p(index: Int, targets: Map<String, String>) {
        visitor.visitParameter(index, null, targets.map { Namespace(it.key) to it.value }.toMap())?.visitEnd()
    }

    /**
     * Bind a set of mappings to this method.
     * @param mappings The mappings to bind.
     * @param action A closure to add mappings to the method.
     * @since 0.1.0
     * @see MethodMappingWithMappings
     */
    @JvmName("withMappingsKt")
    fun withMappings(vararg mappings: String, action: MethodMappingWithMappings.() -> Unit) {
        action(MethodMappingWithMappings(this, mappings.toList()))
    }

    /**
     * Bind a set of mappings to this method.
     * @param mappings The mappings to bind.
     * @param action A closure to add mappings to the method.
     * @since 0.1.0
     * @see MethodMappingWithMappings
     */
    fun withMappings(
        mappings: List<String>,
        @DelegatesTo(value = MethodMappingWithMappings::class, strategy = Closure.DELEGATE_FIRST) action: Closure<*>
    ) {
        action.delegate = MethodMappingWithMappings(this, mappings)
        action.resolveStrategy = Closure.DELEGATE_FIRST
        action.call()
    }
}

/**
 * A class to represent a method mapping.
 * with a set of mappings bound to it.
 * @since 0.1.0
 */
@Scoped
class MethodMappingWithMappings(val methodMapping: MethodMapping, val mappings: List<String>) {

    /**
     * Add a parameter mapping.
     * @param index The index of the parameter.
     * @param targets target names (in the order of the mappings passed to [MethodMappingWithMappings])
     * @since 0.1.0
     */
    fun p(index: Int, vararg targets: String?) {
        methodMapping.p(index, (mappings zip targets).associated().nonNullValues())
    }

    /**
     * @since 0.5.0
     */
    fun p(index: Int, targets: List<String?>) {
        p(index, *targets.toTypedArray())
    }
}