package xyz.wagyourtail.unimined.mapping

import groovy.lang.Closure
import groovy.lang.DelegatesTo
import xyz.wagyourtail.unimined.api.util.delegatingTo
import xyz.wagyourtail.unimined.mapping.element.mapping.MutableClassMapping
import xyz.wagyourtail.unimined.mapping.element.mapping.MutableMethodMapping
import xyz.wagyourtail.unimined.mapping.formats.tiny.v2.TinyV2Writer
import xyz.wagyourtail.unimined.mapping.sink.emit

/**
 * The `stubs { }` block scope — inline patch rows layered onto the
 * environment's composed mappings (1.x `stubs(*namespaces) { }`).
 *
 * Cell values are positional across the namespaces declared on the
 * `stubs(...)` call; a `null` cell leaves that namespace unmapped.
 * `c`/`f`/`m` are the 1.x verb names; `clazz`/`field`/`method` (and
 * the rest of [MappingTreeDsl]'s surface — `pkg`, `parameter`,
 * `wildcard`, tweaker directives) work too.
 *
 * ```kotlin
 * mappings {
 *     stubs("official", "named") {
 *         c("a/b", "com/example/Foo") {
 *             m("c;()V", "run")
 *         }
 *     }
 * }
 * ```
 */
open class StubsSpec(namespaces: List<String>) : MappingTreeDsl(namespaces) {

    /** Add a class stub row — 1.x `c(...)` verb. */
    fun c(vararg names: String?, block: StubsClassDsl.() -> Unit = {}) =
        clazz(*names) { (this as StubsClassDsl).block() }

    /** Groovy DSL twin — `c(["a/b", "com/example/Foo"]) { ... }`. */
    fun c(
        names: List<String?>,
        @DelegatesTo(value = StubsClassDsl::class, strategy = Closure.DELEGATE_FIRST)
        block: Closure<*>
    ) = c(*names.toTypedArray()) { block.delegatingTo(this) }

    /** Serialize the declared rows as a tiny-v2 document — the form
     *  [MappingsSpecImpl.Source.Stubs] materializes for the mapping
     *  pipeline. */
    fun serialize(): ByteArray = buildString {
        tree.emit(TinyV2Writer.write(::append))
    }.toByteArray(Charsets.UTF_8)

    override fun classScope(cls: MutableClassMapping): ClassDsl =
        StubsClassDsl(this, cls)

    /** Member scope inside [c]/[clazz] with the 1.x `f`/`m` verbs. */
    open class StubsClassDsl(
        treeDsl: MappingTreeDsl,
        cls: MutableClassMapping
    ) : ClassDsl(treeDsl, cls) {

        /** Add a field stub row — 1.x `f(...)` verb; entries are
         *  `name[;desc]` per namespace. */
        fun f(vararg entries: String?, block: MetaDsl.() -> Unit = {}) =
            field(*entries, block = block)

        /** Groovy DSL twin of [f]. */
        fun f(
            entries: List<String?>,
            @DelegatesTo(value = MetaDsl::class, strategy = Closure.DELEGATE_FIRST)
            block: Closure<*>
        ) = f(*entries.toTypedArray()) { block.delegatingTo(this) }

        /** Add a method stub row — 1.x `m(...)` verb; entries are
         *  `name[;desc]` per namespace. */
        fun m(
            vararg entries: String?,
            block: MethodDsl.() -> Unit = {}
        ): MutableMethodMapping = method(*entries, block = block)

        /** Groovy DSL twin of [m]. */
        fun m(
            entries: List<String?>,
            @DelegatesTo(value = MethodDsl::class, strategy = Closure.DELEGATE_FIRST)
            block: Closure<*>
        ) = m(*entries.toTypedArray()) { block.delegatingTo(this) }
    }
}
