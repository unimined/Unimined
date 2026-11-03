package xyz.wagyourtail.unimined.api.mapping

import groovy.lang.Closure
import groovy.lang.DelegatesTo
import xyz.wagyourtail.unimined.mapping.MappingTreeDsl
import xyz.wagyourtail.unimined.mapping.MappingTreeDslMarker
import xyz.wagyourtail.unimined.mapping.MutableMappingTree
import xyz.wagyourtail.unimined.mapping.element.InlineType
import xyz.wagyourtail.unimined.mapping.element.WildcardType
import xyz.wagyourtail.unimined.mapping.element.mapping.MutableClassMapping
import xyz.wagyourtail.unimined.mapping.element.mapping.MutableConstantGroupMapping
import xyz.wagyourtail.unimined.mapping.element.mapping.MutableMethodMapping

/**
 * Groovy-facing entry point for the UML [MappingTreeDsl] — the Kotlin
 * `mappingTree` builder's `X.() -> Unit` blocks are `Function1`s, which
 * `Closure`s cannot coerce to, so this scope re-exposes the nested
 * builders as `Closure` overloads with `DELEGATE_FIRST` resolution:
 *
 * ```groovy
 * mappingTree("official", "named") {
 *     pkg "a/", "b/"
 *     clazz("a/A", "b/B") {
 *         method("m;()V", "n") {
 *             parameter 0, null, "arg"
 *         }
 *     }
 * }
 * ```
 *
 * @return the built tree; embed in spec surfaces that accept
 *  `MutableMappingTree`/`MappingTree`
 */
fun mappingTree(
    vararg namespaces: String,
    @DelegatesTo(value = GroovyMappingTreeDsl::class, strategy = Closure.DELEGATE_FIRST)
    block: Closure<*>
): MutableMappingTree = GroovyMappingTreeDsl(namespaces.toList()).apply {
    block.delegate = this
    block.resolveStrategy = Closure.DELEGATE_FIRST
    block.call()
}.tree

/**
 * Groovy [MappingTreeDsl] — adds `Closure` overloads and routes nested
 * scopes through [GroovyClassDsl]/[GroovyMethodDsl] so unqualified
 * member calls (`method`, `field`, …) resolve inside closures.
 *
 * No-block variants are `override`s, not overloads: Groovy can't see
 * Kotlin's `= {}` default args, so the leaf-row `block` parameters
 * would otherwise make `parameter(0, null, "arg")` uncallable.
 */
@MappingTreeDslMarker
class GroovyMappingTreeDsl(namespaces: List<String>) : MappingTreeDsl(namespaces) {

    /** Groovy arity — the base's `block` param is invisible to Groovy. */
    fun pkg(vararg names: String?) = pkg(*names) {}

    /** `pkg { }` Groovy overload — delegates the closure to a [MetaDsl]. */
    fun pkg(
        vararg names: String?,
        @DelegatesTo(value = MetaDsl::class, strategy = Closure.DELEGATE_FIRST)
        block: Closure<*>
    ) = pkg(*names) {
        block.delegate = this
        block.resolveStrategy = Closure.DELEGATE_FIRST
        block.call()
    }

    /** `clazz { }` Groovy overload — delegates the closure to a [GroovyClassDsl]. */
    fun clazz(
        vararg names: String?,
        @DelegatesTo(value = GroovyClassDsl::class, strategy = Closure.DELEGATE_FIRST)
        block: Closure<*>
    ): MutableClassMapping = clazz(*names) {
        block.delegate = this
        block.resolveStrategy = Closure.DELEGATE_FIRST
        block.call()
    }

    /** `constantGroup { }` Groovy overload. */
    fun constantGroup(
        type: InlineType,
        name: String?,
        baseNs: String,
        @DelegatesTo(value = ConstantGroupDsl::class, strategy = Closure.DELEGATE_FIRST)
        block: Closure<*>
    ): MutableConstantGroupMapping = constantGroup(type, name, baseNs) {
        block.delegate = this
        block.resolveStrategy = Closure.DELEGATE_FIRST
        block.call()
    }

    override fun classScope(cls: MutableClassMapping): ClassDsl =
        GroovyClassDsl(this, cls)

    /** Member scope inside [clazz] with `method {}` Groovy support. */
    @MappingTreeDslMarker
    class GroovyClassDsl(
        treeDsl: MappingTreeDsl,
        cls: MutableClassMapping
    ) : ClassDsl(treeDsl, cls) {

        /** Groovy arity — the base's `block` param is invisible to Groovy. */
        fun field(vararg entries: String?) = field(*entries) {}

        /** `field { }` Groovy overload — delegates the closure to a [MetaDsl]. */
        fun field(
            vararg entries: String?,
            @DelegatesTo(value = MetaDsl::class, strategy = Closure.DELEGATE_FIRST)
            block: Closure<*>
        ) = field(*entries) {
            block.delegate = this
            block.resolveStrategy = Closure.DELEGATE_FIRST
            block.call()
        }

        /** Groovy arity. */
        fun wildcard(type: WildcardType, vararg descs: String?) = wildcard(type, *descs) {}

        /** `wildcard { }` Groovy overload. */
        fun wildcard(
            type: WildcardType,
            vararg descs: String?,
            @DelegatesTo(value = MetaDsl::class, strategy = Closure.DELEGATE_FIRST)
            block: Closure<*>
        ) = wildcard(type, *descs) {
            block.delegate = this
            block.resolveStrategy = Closure.DELEGATE_FIRST
            block.call()
        }

        /** `method { }` Groovy overload — delegates to a [GroovyMethodDsl]. */
        fun method(
            vararg entries: String?,
            @DelegatesTo(value = GroovyMethodDsl::class, strategy = Closure.DELEGATE_FIRST)
            block: Closure<*>
        ): MutableMethodMapping = method(*entries) {
            block.delegate = this
            block.resolveStrategy = Closure.DELEGATE_FIRST
            block.call()
        }

        override fun methodScope(method: MutableMethodMapping): MethodDsl =
            GroovyMethodDsl(treeDsl, method)
    }

    /** Scope inside [GroovyClassDsl.method] — restores Groovy arity for
     *  the `vararg`-trailing leaf methods (`block` param is invisible
     *  to Groovy). */
    @MappingTreeDslMarker
    class GroovyMethodDsl(
        treeDsl: MappingTreeDsl,
        method: MutableMethodMapping
    ) : MethodDsl(treeDsl, method) {

        /** Groovy arity — Kotlin callers use the `block`-accepting base. */
        fun parameter(index: Int?, lvOrd: Int?, vararg names: String?) =
            parameter(index, lvOrd, *names) {}

        /** `parameter { }` Groovy overload — delegates to a [MetaDsl]. */
        fun parameter(
            index: Int?,
            lvOrd: Int?,
            vararg names: String?,
            @DelegatesTo(value = MetaDsl::class, strategy = Closure.DELEGATE_FIRST)
            block: Closure<*>
        ) = parameter(index, lvOrd, *names) {
            block.delegate = this
            block.resolveStrategy = Closure.DELEGATE_FIRST
            block.call()
        }

        /** Groovy arity. */
        fun localVariable(lvOrd: Int, startOp: Int?, vararg names: String?) =
            localVariable(lvOrd, startOp, *names) {}

        /** `localVariable { }` Groovy overload. */
        fun localVariable(
            lvOrd: Int,
            startOp: Int?,
            vararg names: String?,
            @DelegatesTo(value = MetaDsl::class, strategy = Closure.DELEGATE_FIRST)
            block: Closure<*>
        ) = localVariable(lvOrd, startOp, *names) {
            block.delegate = this
            block.resolveStrategy = Closure.DELEGATE_FIRST
            block.call()
        }
    }

}
