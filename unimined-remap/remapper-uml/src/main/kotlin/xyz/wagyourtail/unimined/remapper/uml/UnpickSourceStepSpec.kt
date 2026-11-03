package xyz.wagyourtail.unimined.remapper.uml

import org.gradle.api.Action
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import xyz.wagyourtail.unimined.remap.RemapSurfaceSpec
import xyz.wagyourtail.unimined.remap.spi.StepSpec

/**
 * Spec for the `unpick` sources step — constant uninlining on generated
 * sources via UML's `ConstantGroupSourceExtension`.
 */
abstract class UnpickSourceStepSpec : StepSpec {
    @get:Input
    @get:Optional
    override val engine: String?
        get() = null


    /**
     * Extra unpick definition files (`*.unpick`, or any UML-readable
     * constant-group source) merged into the environment tree's constant
     * groups for this step. The mapping tree's own groups always apply.
     */
    @get:Optional
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val defs: ConfigurableFileCollection

}

/**
 * Kotlin-DSL sugar: `sources { unpick("path/to/defs.unpick") }` or
 * `unpick { defs.from(...) }`.
 */
fun RemapSurfaceSpec.unpick(
    vararg defs: Any,
    action: Action<UnpickSourceStepSpec> = Action {}
): UnpickSourceStepSpec = step(UnpickSourceStepSpec::class.java) { spec ->
    spec.defs.from(*defs)
    action.execute(spec)
}
