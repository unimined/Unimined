package xyz.wagyourtail.unimined.transform.jarmod

import xyz.wagyourtail.unimined.transform.jarmod.internal.JarModMergeProcessor
import xyz.wagyourtail.unimined.transform.jarmod.internal.fg1.FG1FixForgeProcessor
import xyz.wagyourtail.unimined.transform.jarmod.internal.fg1.FMLChecksumPatchProcessor
import xyz.wagyourtail.unimined.transform.processor.JarProcessor
import xyz.wagyourtail.unimined.transform.step.PipelineStepSpec
import xyz.wagyourtail.unimined.transform.step.StepType
import xyz.wagyourtail.unimined.transform.step.StepTypeSource
import java.io.File
import java.nio.file.Path

/**
 * jarmod pipeline step types — the declared-pipeline form of the
 * module's processors. `pipeline { jarmodMerge { } … }` composes
 * these into any env (including `custom`), not just the jarmod
 * transform plugin.
 */

/** `jarmod:merge` — splice declared jars into the MC jar pre-remap. */
object JarModMergeStepType : StepType {
    override val id = "jarmod:merge"
    override val produces = listOf("jarmod:merged")
}

/** Spec for [JarModMergeStepType]. */
abstract class JarModMergeStepSpec : PipelineStepSpec {

    /** JarMod source jars — merged into the MC jar. */
    var jarMod: List<Path> = emptyList()

    /** Strip the base jar's META-INF after merge. */
    var deleteMetaInf: () -> Boolean = { false }

    override fun jarProcessors(): List<JarProcessor> =
        listOf(JarModMergeProcessor(jarMod, deleteMetaInf))

}

/** `jarmod:fg1Fix` — FG1-era forge dev fixes. */
object FG1FixStepType : StepType {
    override val id = "jarmod:fg1Fix"
}

/** Spec for [FG1FixStepType] — no options. */
abstract class FG1FixStepSpec : PipelineStepSpec {

    override fun jarProcessors(): List<JarProcessor> =
        listOf(FG1FixForgeProcessor())

}

/** `jarmod:checksumPatch` — FML checksum patch artifact injection. */
object ChecksumPatchStepType : StepType {
    override val id = "jarmod:checksumPatch"
}

/** Spec for [ChecksumPatchStepType]. */
abstract class ChecksumPatchStepSpec : PipelineStepSpec {

    /** Named artifacts injected into the checksum patch file. */
    var artifacts: () -> Map<String, File> = { emptyMap() }

    override fun jarProcessors(): List<JarProcessor> =
        listOf(FMLChecksumPatchProcessor(artifacts))

}

/** ServiceLoader source — jarmod's step-type feature pack. */
class JarModStepTypeSource : StepTypeSource {

    override fun stepTypes(): List<StepType> = listOf(
        JarModMergeStepType,
        FG1FixStepType,
        ChecksumPatchStepType,
    )

}
