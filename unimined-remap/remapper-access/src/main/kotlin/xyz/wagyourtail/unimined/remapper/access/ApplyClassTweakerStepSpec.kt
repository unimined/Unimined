package xyz.wagyourtail.unimined.remapper.access

import org.gradle.api.Action
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import xyz.wagyourtail.unimined.remap.RemapSurfaceSpec
import xyz.wagyourtail.unimined.remap.spi.DevArtifactOnlyStepSpec
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional

/**
 * Spec for the `apply-class-tweaker` step: rewrites class access flags
 * in the artifact according to the supplied AW/CT/AT files.
 *
 * Directives are interpreted in the namespace declared by each file's
 * header and remapped to the pipeline's target namespace through the
 * resolved mapping tree before application.
 *
 * [DevArtifactOnlyStepSpec]: these files widen *Minecraft* classes —
 * running this over a production jar or mod dependency would target
 * classes that aren't there.
 */
abstract class ApplyClassTweakerStepSpec : DevArtifactOnlyStepSpec {
    @get:Input
    @get:Optional
    override val engine: String?
        get() = null


    /**
     * Access files to apply (`*.accesswidener`/`*.classTweaker`/`*_at.cfg`)
     * — the project's own declarations, applied in full (`transitive-*`
     * directives apply to the declaring mod too).
     */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val classTweakerFiles: ConfigurableFileCollection

    /**
     * Access files extracted from mod dependencies. Only their
     * `transitive-*` directives are applied — the dependency-application
     * scope, mirroring Loom's `TransitiveOnlyFilter`; a dependency's
     * private widening does not leak onto dependents' compile classpaths.
     */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val dependencyClassTweakerFiles: ConfigurableFileCollection

    /**
     * The real pipeline namespace behind a headerless file's literal
     * `source` namespace (headerless `*_at.cfg` files carry no
     * namespace declaration). User-authored ATs are written in dev
     * names (1.x `at2aw` defaulted to the dev namespace); ATs
     * extracted from a loader's installer are in the loader's obf
     * namespace — the wiring binds this accordingly. Unset: `source`
     * is used as-is (and errors if it's not a tree namespace).
     */
    @get:Optional
    @get:Input
    abstract val headerlessNamespace: Property<String>

}

/**
 * Kotlin-DSL sugar:
 * `jar { applyClassTweaker { classTweakerFiles.from(ct) } }`.
 */
fun RemapSurfaceSpec.applyClassTweaker(
    action: Action<ApplyClassTweakerStepSpec> = Action {}
): ApplyClassTweakerStepSpec = step(ApplyClassTweakerStepSpec::class.java, action)
