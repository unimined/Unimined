package xyz.wagyourtail.unimined.mapping.internal

import xyz.wagyourtail.unimined.mapping.MutableMappingTree
import xyz.wagyourtail.unimined.mapping.Namespace
import xyz.wagyourtail.unimined.mapping.jvms.four.two.one.InternalName

/**
 * Rewrites class cells in [namespaces] containing [separator] — the
 * feather/enigma `Outer__Inner` Java-identifier marker for nested
 * classes — to the JVM `Outer$Inner` binary form. Hosts apply this at
 * read time for source columns declared to use the marker, so the
 * in-memory tree (and every downstream remap view, export and cache)
 * always carries real bytecode names instead of each consumer
 * re-implementing the translation.
 *
 * Cells without the marker and classes whose [namespaces] cell is
 * absent are untouched; a legitimately-marker-named class therefore
 * still gets rewritten — matches the historical feather-source
 * behavior where `__` in a named column is the marker by convention.
 */
internal fun MutableMappingTree.dollarInnerClassNames(
    namespaces: Set<Namespace>,
    separator: String = "__"
) {
    // collect first — clazz() upserts mutate the name index, so
    // rewriting while iterating `classes` would rekey mid-stream
    val pending = mutableListOf<Map<Namespace, InternalName>>()
    for (cls in classes) {
        var names: MutableMap<Namespace, InternalName>? = null
        for (ns in namespaces) {
            val name = cls.names[ns] ?: continue
            if (separator !in name.value) continue
            if (names == null) names = cls.names.toMutableMap()
            names[ns] = InternalName.unchecked(name.value.replace(separator, "$"))
        }
        if (names != null) pending += names
    }
    for (names in pending) clazz(names)
}
