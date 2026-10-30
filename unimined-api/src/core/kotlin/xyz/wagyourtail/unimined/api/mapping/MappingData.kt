package xyz.wagyourtail.unimined.api.mapping

import kotlin.reflect.KClass

/**
 * Resolved mapping data for an environment, exposed as typed views.
 *
 * The canonical view is the UML
 * [xyz.wagyourtail.unimined.mapping.MappingTree] — request it with
 * `data.view(MappingTree::class.java)`. Format-specific extras (unpick
 * groups, parchment, ...) may ride alongside as additional view kinds.
 */
interface MappingData {

    /** The view kinds this data exposes — composers/serializers enumerate it. */
    val viewTypes: Set<KClass<*>>

    /**
     * @return the requested view, or `null` if the resolved mapping data
     *  does not provide that view kind
     */
    fun <T : Any> view(type: Class<T>): T?

    /**
     * Source provenance and version metadata — conventional keys:
     * `channel`/`coordinate` (what produced it), `version` (declared
     * artifact version), `mcVersion` (Minecraft version the artifact
     * targets). Composers merge contributed property maps union-style
     * (later sources win per key).
     */
    val properties: Map<String, String>
        get() = emptyMap()

}

/**
 * Kotlin convenience for [MappingData.view].
 */
inline fun <reified T : Any> MappingData.view(): T? = view(T::class.java)

/** Property key recording the resolved named namespaces. */
const val NAMED_NAMESPACES_KEY = "namedNamespaces"

/**
 * The named namespaces of the resolved mapping data — sources flag
 * them via [MappingSource.namedNamespaces] (plus the `"named"`
 * document-column convention); the resolver records them here in
 * declaration order.
 */
fun MappingData.namedNamespaces(): List<String> =
    properties[NAMED_NAMESPACES_KEY]?.split(",") ?: emptyList()
