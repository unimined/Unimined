package xyz.wagyourtail.unimined.api.mapping

import xyz.wagyourtail.unimined.mapping.MappingTree

/**
 * [MappingData] over a deserialized [MappingBundle] — exposes the UML
 * [MappingTree] rebuilt from the row payload. Used by tasks and
 * transforms to reconstruct mapping data from the serialized
 * interchange without re-resolving sources.
 */
class BundleMappingData(
    private val bundle: MappingBundle
) : MappingData {

    private val tree by lazy { bundle.toUmlTree() }

    override val viewTypes: Set<kotlin.reflect.KClass<*>> =
        setOf(MappingTree::class)

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> view(type: Class<T>): T? =
        if (type == MappingTree::class.java) tree as T else null

}
