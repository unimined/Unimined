package xyz.wagyourtail.unimined.transform.fabric

import org.gradle.api.Action
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import xyz.wagyourtail.unimined.remap.RemapSurfaceSpec
import xyz.wagyourtail.unimined.remap.spi.StepSpec

/**
 * Spec for the `apply-access-widener` step: rewrites class access flags
 * in the artifact according to the supplied Access Widener files.
 *
 * AW directives are interpreted in the namespace declared by each file's
 * `accessWidener` header and remapped to the pipeline's target namespace
 * through the resolved mapping tree before application.
 */
abstract class ApplyAccessWidenerStepSpec : StepSpec {

    /** Access Widener files to apply (`*.accesswidener`). */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val accessWidenerFiles: ConfigurableFileCollection

}

/**
 * Kotlin-DSL sugar:
 * `jar { applyAccessWidener { accessWidenerFiles.from(aw) } }`.
 */
fun RemapSurfaceSpec.applyAccessWidener(
    action: Action<ApplyAccessWidenerStepSpec> = Action {}
): ApplyAccessWidenerStepSpec = step(ApplyAccessWidenerStepSpec::class.java, action)
