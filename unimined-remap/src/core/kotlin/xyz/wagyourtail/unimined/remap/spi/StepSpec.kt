package xyz.wagyourtail.unimined.remap.spi

import java.io.Serializable

/**
 * Marker for the DSL spec of a declared pipeline step — e.g. `unpick`'s
 * definitions file set, `jst`'s parchment/AT config.
 *
 * The concrete spec type is defined by the step type's engine and maps to
 * the implementation via [RemapStepType.specType].
 *
 * Specs are serialized into task inputs (configuration cache) and must be
 * [Serializable]. Model spec state as Gradle `Property`/
 * `ConfigurableFileCollection` fields (or `@Input`/`@Nested`-annotated
 * getters) so pipelines fingerprint correctly — plain serializable fields
 * serialize fine but contribute nothing to up-to-date checks.
 */
interface StepSpec : Serializable {

    /**
     * Name of the engine impl to use for this step ([Named.name] of the
     * desired [RemapStepType]), or `null` to let the shared selection
     * machinery pick via `supports(...)`.
     */
    val engine: String?
        get() = null

}
