package xyz.wagyourtail.unimined.api.mapping

import groovy.lang.Closure
import xyz.wagyourtail.unimined.api.util.callDelegate
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
 * mappingTree(["official", "named"]) {
 *     pkg "a/", "b/"
 *     clazz(["a/A", "b/B"]) {
 *         method(["m;()V", "n"]) {
 *             parameter 0, null, "arg"
 *         }
 *     }
 * }
 * ```
 *
 * Block-taking verbs take a `List` first arg rather than `vararg` —
 * Groovy only packs varargs at the last parameter position, so a
 * `(vararg, Closure)` overload can never dispatch. Bare calls
 * (`pkg "a/", "b/"`, `parameter 0, null, "arg"`) keep the vararg
 * no-block overloads.
 *
 * @return the built tree; embed in spec surfaces that accept
 *  `MutableMappingTree`/`MappingTree`
 */
fun mappingTree(
    namespaces: List<String>,
    @DelegatesTo(value = GroovyMappingTreeDsl::class, strategy = Closure.DELEGATE_FIRST)
    block: Closure<*>
): MutableMappingTree = GroovyMappingTreeDsl(namespaces).apply {
    block.delegate = this
    block.resolveStrategy = Closure.DELEGATE_FIRST
    block.callDelegate()
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
open class GroovyMappingTreeDsl(namespaces: List<String>) : MappingTreeDsl(namespaces) {

    /** Groovy arity — the base's `block` param is invisible to Groovy. */
    fun pkg(vararg names: String?) = pkg(*names) {}

    /** `pkg(["a/", "b/"]) { }` Groovy overload — List+Closure (a
     *  `(vararg, Closure)` signature never dispatches in Groovy). */
    fun pkg(
        names: List<String?>,
        @DelegatesTo(value = MetaDsl::class, strategy = Closure.DELEGATE_FIRST)
        block: Closure<*>
    ) = pkg(*names.toTypedArray()) {
        block.delegate = this
        block.resolveStrategy = Closure.DELEGATE_FIRST
        block.callDelegate()
    }

    /** `clazz(["a/A", "b/B"]) { }` Groovy overload — delegates the
     *  closure to a [GroovyClassDsl]. */
    fun clazz(
        names: List<String?>,
        @DelegatesTo(value = GroovyClassDsl::class, strategy = Closure.DELEGATE_FIRST)
        block: Closure<*>
    ): MutableClassMapping = clazz(*names.toTypedArray()) {
        block.delegate = this
        block.resolveStrategy = Closure.DELEGATE_FIRST
        block.callDelegate()
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
        block.callDelegate()
    }

    override fun classScope(cls: MutableClassMapping): ClassDsl =
        GroovyClassDsl(this, cls)

    /** Member scope inside [clazz] with `method {}` Groovy support. */
    @MappingTreeDslMarker
    open class GroovyClassDsl(
        treeDsl: MappingTreeDsl,
        cls: MutableClassMapping
    ) : ClassDsl(treeDsl, cls) {

        /** Groovy arity — the base's `block` param is invisible to Groovy. */
        fun field(vararg entries: String?) = field(*entries) {}

        /** `field(["f;I", "g"]) { }` Groovy overload — delegates the
         *  closure to a [MetaDsl]. */
        fun field(
            entries: List<String?>,
            @DelegatesTo(value = MetaDsl::class, strategy = Closure.DELEGATE_FIRST)
            block: Closure<*>
        ) = field(*entries.toTypedArray()) {
            block.delegate = this
            block.resolveStrategy = Closure.DELEGATE_FIRST
            block.callDelegate()
        }

        /** Groovy arity. */
        fun wildcard(type: WildcardType, vararg descs: String?) = wildcard(type, *descs) {}

        /** `wildcard(type, ["d1", "d2"]) { }` Groovy overload. */
        fun wildcard(
            type: WildcardType,
            descs: List<String?>,
            @DelegatesTo(value = MetaDsl::class, strategy = Closure.DELEGATE_FIRST)
            block: Closure<*>
        ) = wildcard(type, *descs.toTypedArray()) {
            block.delegate = this
            block.resolveStrategy = Closure.DELEGATE_FIRST
            block.callDelegate()
        }

        /** `method(["m;()V", "n"]) { }` Groovy overload — delegates
         *  to a [GroovyMethodDsl]. */
        fun method(
            entries: List<String?>,
            @DelegatesTo(value = GroovyMethodDsl::class, strategy = Closure.DELEGATE_FIRST)
            block: Closure<*>
        ): MutableMethodMapping = method(*entries.toTypedArray()) {
            block.delegate = this
            block.resolveStrategy = Closure.DELEGATE_FIRST
            block.callDelegate()
        }

        override fun methodScope(method: MutableMethodMapping): MethodDsl =
            GroovyMethodDsl(treeDsl, method)
    }

    /** Scope inside [GroovyClassDsl.method] — restores Groovy arity for
     *  the `vararg`-trailing leaf methods (`block` param is invisible
     *  to Groovy). */
    @MappingTreeDslMarker
    open class GroovyMethodDsl(
        treeDsl: MappingTreeDsl,
        method: MutableMethodMapping
    ) : MethodDsl(treeDsl, method) {

        /** Groovy arity — Kotlin callers use the `block`-accepting base. */
        fun parameter(index: Int?, lvOrd: Int?, vararg names: String?) =
            parameter(index, lvOrd, *names) {}

        /** `parameter(index, lvOrd, ["a", "b"]) { }` Groovy overload —
         *  delegates to a [MetaDsl]. */
        fun parameter(
            index: Int?,
            lvOrd: Int?,
            names: List<String?>,
            @DelegatesTo(value = MetaDsl::class, strategy = Closure.DELEGATE_FIRST)
            block: Closure<*>
        ) = parameter(index, lvOrd, *names.toTypedArray()) {
            block.delegate = this
            block.resolveStrategy = Closure.DELEGATE_FIRST
            block.callDelegate()
        }

        /** Groovy arity. */
        fun localVariable(lvOrd: Int, startOp: Int?, vararg names: String?) =
            localVariable(lvOrd, startOp, *names) {}

        /** `localVariable(lvOrd, startOp, ["v"]) { }` Groovy overload. */
        fun localVariable(
            lvOrd: Int,
            startOp: Int?,
            names: List<String?>,
            @DelegatesTo(value = MetaDsl::class, strategy = Closure.DELEGATE_FIRST)
            block: Closure<*>
        ) = localVariable(lvOrd, startOp, *names.toTypedArray()) {
            block.delegate = this
            block.resolveStrategy = Closure.DELEGATE_FIRST
            block.callDelegate()
        }
    }

}
