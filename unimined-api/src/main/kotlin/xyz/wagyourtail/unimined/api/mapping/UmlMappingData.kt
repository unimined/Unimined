package xyz.wagyourtail.unimined.api.mapping

/**
 * [MappingData] over a resolved UML [xyz.wagyourtail.unimined.mapping.MappingTree] —
 * exposes both the rich [UmlMappingTreeView] (full tree incl. metadata) and
 * the portable [MappingTree] view (via [UmlMappingTreeAdapter]).
 */
class UmlMappingData(
    uml: xyz.wagyourtail.unimined.mapping.MappingTree
) : MappingData {

    private val umlView = object : UmlMappingTreeView {
        override val tree = uml
    }

    private val portable = UmlMappingTreeAdapter(uml)

    override val viewTypes: Set<kotlin.reflect.KClass<out MappingDataView>> =
        setOf(UmlMappingTreeView::class, MappingTree::class)

    @Suppress("UNCHECKED_CAST")
    override fun <T : MappingDataView> view(type: Class<T>): T? = when (type) {
        UmlMappingTreeView::class.java -> umlView as T
        MappingTree::class.java -> portable as T
        else -> null
    }

}
