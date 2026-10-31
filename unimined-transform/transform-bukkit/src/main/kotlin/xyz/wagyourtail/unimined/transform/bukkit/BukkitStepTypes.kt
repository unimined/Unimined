package xyz.wagyourtail.unimined.transform.bukkit

import xyz.wagyourtail.unimined.transform.bukkit.internal.PaperPatchProcessor
import xyz.wagyourtail.unimined.transform.bukkit.internal.SpigotNmsRelocationProcessor
import xyz.wagyourtail.unimined.transform.processor.JarProcessor
import xyz.wagyourtail.unimined.transform.step.PipelineStepSpec
import xyz.wagyourtail.unimined.transform.step.StepType
import xyz.wagyourtail.unimined.transform.step.StepTypeSource
import java.nio.file.Path

/**
 * bukkit pipeline step types — the declared-pipeline form of the
 * module's processors. `pipeline { paperPatch { } … }` composes
 * these into any env (including `custom`), not just the bukkit
 * transform plugin.
 */

/** `bukkit:paperPatch` — apply the paperclip patch set to the
 *  nested bundler server jar. */
object PaperPatchStepType : StepType {
    override val id = "bukkit:paperPatch"
    override val produces = listOf("bukkit:paperPatched")
}

/** Spec for [PaperPatchStepType]. */
abstract class PaperPatchStepSpec : PipelineStepSpec {

    /** The paperclip distribution jar carrying `patches.list`. */
    var distJar: () -> Path = { throw IllegalStateException("paperPatch requires distJar") }

    override fun jarProcessors(): List<JarProcessor> =
        listOf(PaperPatchProcessor(distJar))

}

/** `bukkit:nmsRelocate` — spigot versioned-NMS package relocation. */
object NmsRelocateStepType : StepType {
    override val id = "bukkit:nmsRelocate"
    override val produces = listOf("bukkit:nmsRelocated")
    override val runsAfter = setOf("bukkit:paperPatch")
}

/** Spec for [NmsRelocateStepType]. */
abstract class NmsRelocateStepSpec : PipelineStepSpec {

    /** The versioned NMS package token — `v1_20_R3`-style. */
    var versionPackage: () -> String = { throw IllegalStateException("nmsRelocate requires versionPackage") }

    override fun jarProcessors(): List<JarProcessor> =
        listOf(SpigotNmsRelocationProcessor(versionPackage))

}

/** ServiceLoader source — bukkit's step-type feature pack. */
class BukkitStepTypeSource : StepTypeSource {

    override fun stepTypes(): List<StepType> = listOf(
        PaperPatchStepType,
        NmsRelocateStepType,
    )

}
