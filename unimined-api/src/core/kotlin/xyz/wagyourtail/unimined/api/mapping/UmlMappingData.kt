package xyz.wagyourtail.unimined.api.mapping

import xyz.wagyourtail.unimined.mapping.MappingTree

/**
 * [MappingData] over a resolved UML [MappingTree] — the canonical,
 * fully-featured view (rename rows plus metadata: `AccessElement`s,
 * `SignatureElement`s, comments, constant groups).
 */
class UmlMappingData(
    /** The resolved mapping tree. */
    val tree: MappingTree,
    override val properties: Map<String, String> = emptyMap()
) : MappingData {

    override val viewTypes: Set<kotlin.reflect.KClass<*>> =
        setOf(MappingTree::class)

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> view(type: Class<T>): T? =
        if (type == MappingTree::class.java) tree as T else null

}
