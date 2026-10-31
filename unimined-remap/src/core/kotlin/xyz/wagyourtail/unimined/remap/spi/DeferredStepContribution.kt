package xyz.wagyourtail.unimined.remap.spi

/**
 * One step contribution computed lazily by a transform plugin —
 * the deferred form of `contributeStep`/`contributePreStep` for
 * contributions whose spec set isn't known at wiring time (artifact
 * probes, loader-resolution-dependent steps).
 *
 * Registered through
 * `RemapEnvironmentSpec.contributeDeferredSteps(provider)` — the
 * provider evaluates when the surface's pipeline materializes, so
 * resolution-heavy decisions never run during configuration.
 */
data class DeferredStepContribution(

    /** The surface this step runs on. */
    val surface: RemapSurface,

    /** The step's DSL spec. */
    val spec: StepSpec,

    /** `contributePreStep` — runs before the declared pipeline, on
     *  the original-namespace input. */
    val pre: Boolean = false,

    val scope: StepScope = StepScope.ALL_ARTIFACTS,
)
