package xyz.wagyourtail.unimined.mod

import org.gradle.api.provider.MapProperty

/**
 * Manifest attributes for an era-merge output jar. Values are stringified so
 * legacy loader flags can be written naturally as booleans/numbers in build
 * scripts.
 */
abstract class EraMergeManifestSpec {

    /** Attribute name to single value. See [EraMergeSpec] for merge semantics. */
    abstract val attributes: MapProperty<String, String>

    /** Set one manifest attribute. */
    fun attribute(name: String, value: Any) {
        attributes.put(name, value.toString())
    }

    /** Set several manifest attributes. */
    fun attributes(vararg values: Pair<String, Any>) {
        values.forEach { (name, value) -> attribute(name, value) }
    }

    /** Set several manifest attributes from a map. */
    fun attributes(values: Map<String, Any>) {
        values.forEach { (name, value) -> attribute(name, value) }
    }

}
