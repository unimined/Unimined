package xyz.wagyourtail.unimined.transform.forge

import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import xyz.wagyourtail.unimined.remap.spi.RemapSurface
import xyz.wagyourtail.unimined.transform.forge.internal.binpatch.BinpatchesPackStepSpec
import xyz.wagyourtail.unimined.transform.forge.processor.UserdevInjectProcessor
import xyz.wagyourtail.unimined.transform.forge.processor.UserdevUniversalShadeProcessor
import xyz.wagyourtail.unimined.transform.processor.JarProcessor
import xyz.wagyourtail.unimined.transform.step.PipelineStepSpec
import xyz.wagyourtail.unimined.transform.step.RemapStepContribution
import xyz.wagyourtail.unimined.transform.step.StepType
import xyz.wagyourtail.unimined.transform.step.StepTypeSource
import javax.inject.Inject

/**
 * forge pipeline step types — the declared-pipeline form of the
 * module's machinery. `pipeline { forgeBinpatches { } … }` composes
 * these into any env (including `custom`), not just the forge
 * transform plugin.
 */

/** `forge:binpatches` — Binpatches-era binary patch pack step. */
object ForgeBinpatchesStepType : StepType {
    override val id = "forge:binpatches"
    override val produces = listOf("forge:binpatch")
    // srg-named jar arrives first — userdev-era and merges feed it
    override val runsAfter = setOf("forge:userdevExec", "forge:mergeInputs")
}

/**
 * Spec for [ForgeBinpatchesStepType] — mirrors
 * [BinpatchesPackStepSpec]'s fields; `stepSpecs()` materializes the
 * Gradle-decorated artifact spec contributed to the JAR surface.
 */
abstract class ForgeBinpatchesStepSpec @Inject constructor(
    private val objects: ObjectFactory
) : PipelineStepSpec {

    abstract val loaderJar: RegularFileProperty
    abstract val packEntry: Property<String>
    abstract val side: Property<String>
    abstract val clientJar: RegularFileProperty
    abstract val serverJar: RegularFileProperty

    override fun stepSpecs(): List<RemapStepContribution> = listOf(
        RemapStepContribution(
            RemapSurface.JAR,
            objects.newInstance(BinpatchesPackStepSpec::class.java).also {
                it.loaderJar.set(loaderJar)
                it.packEntry.set(packEntry)
                it.side.set(side)
                it.clientJar.set(clientJar)
                it.serverJar.set(serverJar)
            }
        )
    )

}

/** `forge:userdevExec` — userdev-era inject + shade (one step per
 *  plan: config.json already describes the complete pipeline). */
object ForgeUserdevExecStepType : StepType {
    override val id = "forge:userdevExec"
    override val produces = listOf("forge:userdev")
}

/** Spec for [ForgeUserdevExecStepType] — no options; the userdev
 *  processors read their config from the env spec context. */
abstract class ForgeUserdevExecStepSpec : PipelineStepSpec {

    override fun jarProcessors(): List<JarProcessor> = listOf(
        UserdevInjectProcessor(),
        UserdevUniversalShadeProcessor(),
    )

}

/** ServiceLoader source — forge's step-type feature pack. */
class ForgeStepTypeSource : StepTypeSource {

    override fun stepTypes(): List<StepType> = listOf(
        ForgeBinpatchesStepType,
        ForgeUserdevExecStepType,
    )

}
