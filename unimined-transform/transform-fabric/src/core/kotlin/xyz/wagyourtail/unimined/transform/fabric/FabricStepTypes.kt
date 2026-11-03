package xyz.wagyourtail.unimined.transform.fabric

import xyz.wagyourtail.unimined.transform.fabric.processor.ModJavadocJarProcessor
import xyz.wagyourtail.unimined.transform.processor.JarProcessor
import xyz.wagyourtail.unimined.transform.step.PipelineStepSpec
import xyz.wagyourtail.unimined.transform.step.StepType
import xyz.wagyourtail.unimined.transform.step.StepTypeSource

/**
 * fabric pipeline step types — the declared-pipeline form of the
 * module's processors. Composable via `pipeline { }` into any env,
 * including `custom`.
 */

/** `fabric:modJavadoc` — splice mod-provided javadoc entries into
 *  the dev sources jar. */
object ModJavadocStepType : StepType {
    override val id = "fabric:modJavadoc"
}

/** Spec for [ModJavadocStepType] — no options. */
abstract class ModJavadocStepSpec : PipelineStepSpec {

    override fun jarProcessors(): List<JarProcessor> =
        listOf(ModJavadocJarProcessor())

}

/** ServiceLoader source — fabric's step-type feature pack. */
class FabricStepTypeSource : StepTypeSource {

    override fun stepTypes(): List<StepType> = listOf(ModJavadocStepType)

}
