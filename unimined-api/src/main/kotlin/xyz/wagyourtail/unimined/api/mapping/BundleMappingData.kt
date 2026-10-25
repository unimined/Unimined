package xyz.wagyourtail.unimined.api.mapping

/**
 * [MappingData] over a deserialized [MappingBundle] — exposes only the
 * [MappingTree] view. Used by tasks and transforms to reconstruct mapping
 * data from the serialized interchange without re-resolving sources.
 */
class BundleMappingData(
    private val bundle: MappingBundle
) : MappingData {

    override val viewTypes: Set<kotlin.reflect.KClass<out MappingDataView>> =
        setOf(MappingTree::class)

    @Suppress("UNCHECKED_CAST")
    override fun <T : MappingDataView> view(type: Class<T>): T? =
        if (type == MappingTree::class.java) bundle as T else null

}
