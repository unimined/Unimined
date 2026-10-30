package xyz.wagyourtail.unimined.remap.spi

import xyz.wagyourtail.unimined.api.spec.RemapSpec
import xyz.wagyourtail.unimined.api.spi.Supports
import kotlin.reflect.KClass

/**
 * An engine's implementation of a named pipeline operation.
 *
 * [stepName] is the DSL verb the step provides (`"remap"`, `"unpick"`,
 * `"jst"`, `"preen"`, ...) and is *not* unique across engines — two
 * engines may both implement `unpick`. [name] (inherited from
 * [Supports] via [xyz.wagyourtail.unimined.api.spi.Named]) is the
 * engine-unique identifier and the DSL-override target:
 * `remap("tiny")` selects the engine named `"tiny"`.
 *
 * Implementations are discovered via `ServiceLoader` — engine jars must
 * list each impl in
 * `META-INF/services/xyz.wagyourtail.unimined.remap.spi.RemapStepType`
 * and give it a public no-arg constructor, so that a registry constructed
 * at execution time sees the same impls the DSL saw.
 *
 * @param S the step's DSL spec type
 */
interface RemapStepType<S : StepSpec> : Supports<RemapSpec> {

    /** DSL verb this implementation provides. */
    val stepName: String

    /** Surfaces this implementation can run on. */
    val surfaces: Set<RemapSurface>

    /** Spec type consumed by [create]; used to map declared DSL steps to
     *  this implementation. */
    val specType: KClass<S>

    /** Whether [StepContext.mappings] must be materialized for this step. */
    val needsMappingTree: Boolean
        get() = false

    /** Whether [StepContext.classpath] must be materialized for this step. */
    val needsClasspath: Boolean
        get() = false

    /** Whether [StepContext.accessModel] must be materialized for this step. */
    val needsAccessModel: Boolean
        get() = false

    /** Instantiate the executable step for one pipeline run. */
    fun create(spec: S, ctx: StepContext): ArtifactStep

}
