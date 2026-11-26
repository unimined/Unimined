package xyz.wagyourtail.unimined.mod

import groovy.lang.Closure
import groovy.lang.DelegatesTo
import org.gradle.api.Action
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import xyz.wagyourtail.unimined.api.util.delegatingTo
import javax.inject.Inject

/**
 * DSL model shared by `mergedEraJar` and `chimeraJar`.
 *
 * Era selection defaults to bound, versioned, non-auxiliary environments that
 * still have `defaultRemapJar` enabled — the product-side equivalent of the
 * fixture-level "include in output" concept. Calling [eras] replaces that
 * automatic set exactly.
 */
abstract class EraMergeSpec @Inject constructor(
    private val objects: ObjectFactory
) {

    /** Exact era selection when [eras] was called; empty means automatic. */
    abstract val eraNames: ListProperty<String>

    /** Prepend transitive `combineWith` output before era artifacts; default `true`. */
    abstract val mainFirst: Property<Boolean>

    /** Stringly overlap validation: `"warn"`, `"strict"`, or `"off"`; default `"warn"`. */
    abstract val overlapValidation: Property<String>

    /** Nested-jar configuration for eras partitioned as `"nest"`. */
    val nestedJar: NestedEraJarSpec = objects.newInstance(NestedEraJarSpec::class.java)

    internal val manifestSpec: EraMergeManifestSpec =
        objects.newInstance(EraMergeManifestSpec::class.java)

    internal var explicitEras: Boolean = false
        private set

    internal val eraOverrides = linkedMapOf<String, EraInputSpec>()

    init {
        mainFirst.convention(true)
        overlapValidation.convention("warn")
    }

    /** Replace automatic era selection with exactly [names], in merge order. */
    fun eras(vararg names: String) {
        explicitEras = true
        eraNames.set(names.toList())
    }

    /** Configure selection/partition/artifact overrides for one era. */
    fun era(name: String, action: Action<EraInputSpec>) {
        val spec = eraOverrides.getOrPut(name) {
            objects.newInstance(EraInputSpec::class.java)
        }
        action.execute(spec)
    }

    /** Groovy DSL overload for [era]. */
    fun era(
        name: String,
        @DelegatesTo(value = EraInputSpec::class, strategy = Closure.DELEGATE_FIRST)
        action: Closure<*>
    ) {
        era(name, Action<EraInputSpec> { action.delegatingTo(it) })
    }

    /** Convenience for `era(name) { partition.set(partition) }`. */
    fun partition(name: String, partition: String) {
        era(name) { it.partition.set(partition) }
    }

    /** Configure the generated nested jar. */
    fun nested(action: Action<NestedEraJarSpec>) {
        action.execute(nestedJar)
    }

    /** Groovy DSL overload for [nested]. */
    fun nested(
        @DelegatesTo(value = NestedEraJarSpec::class, strategy = Closure.DELEGATE_FIRST)
        action: Closure<*>
    ) {
        action.delegatingTo(nestedJar)
    }

    /** Configure outer-jar manifest attributes. */
    fun manifest(action: Action<EraMergeManifestSpec>) {
        action.execute(manifestSpec)
    }

    /** Groovy DSL overload for [manifest]. */
    fun manifest(
        @DelegatesTo(value = EraMergeManifestSpec::class, strategy = Closure.DELEGATE_FIRST)
        action: Closure<*>
    ) {
        action.delegatingTo(manifestSpec)
    }

    /** Set one outer manifest attribute. */
    fun attribute(name: String, value: Any) = manifestSpec.attribute(name, value)

}
