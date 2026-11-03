package xyz.wagyourtail.unimined.transform.forge

import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import xyz.wagyourtail.unimined.remap.spi.RemapSurface
import xyz.wagyourtail.unimined.transform.forge.internal.MergeInputsTransformStep
import xyz.wagyourtail.unimined.transform.forge.internal.binpatch.BinpatchesPackStepSpec
import xyz.wagyourtail.unimined.transform.forge.processor.UserdevInjectProcessor
import xyz.wagyourtail.unimined.transform.forge.processor.UserdevUniversalShadeProcessor
import xyz.wagyourtail.unimined.transform.processor.JarProcessor
import xyz.wagyourtail.unimined.transform.step.PipelineStepSpec
import xyz.wagyourtail.unimined.transform.step.RemapStepContribution
import xyz.wagyourtail.unimined.transform.step.StepType
import xyz.wagyourtail.unimined.transform.step.StepTypeSource
import xyz.wagyourtail.unimined.transform.step.TransformStepContribution
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

/**
 * `forge:mergeInputs` — merge named env-artifact inputs into the
 * pipeline jar (declared-step form; `custom`-pipeline users
 * assemble the canonical `mergeInputs` step directly).
 */
object ForgeMergeInputsStepType : StepType {
    override val id = "forge:mergeInputs"
    override val produces = listOf("forge:merged")
}

/**
 * Spec for [ForgeMergeInputsStepType]. [inputs] maps a logical
 * input name to an `envArtifactBindings` key — the wiring resolves
 * it inside the deferred provider into
 * `TransformStepContext.inputs` (e.g.
 * `inputs.put("server", "server_jar")`).
 */
abstract class ForgeMergeInputsStepSpec : PipelineStepSpec {

    /** name → `envArtifactBindings` key for each aux input. */
    abstract val inputs: org.gradle.api.provider.MapProperty<String, String>

    override fun transformSteps() = listOf(
        TransformStepContribution(
            RemapSurface.JAR,
            MergeInputsTransformStep,
            inputBindings = inputs.get(),
        )
    )

}

/**
 * `forge:srgRuntime` — opt-in FG2 srg-namespace dev launch. A mode
 * step: when declared, the run wiring swaps the launch MC artifact
 * to the mod-source (searge) view and emits
 * `-Dunimined.fg2.runtimeDeobf` so FML's runtime deobf transformer
 * stays registered — [DeobfTweakerTx]'s strip is inert. Without it
 * (default, 1.x behavior) the dev-mapped jar runs directly.
 */
object ForgeSrgRuntimeStepType : StepType {
    override val id = "forge:srgRuntime"
    override val produces = listOf("forge:srg")
}

/** Spec for [ForgeSrgRuntimeStepType] — no options; declaring the
 *  step is the signal. */
abstract class ForgeSrgRuntimeStepSpec : PipelineStepSpec

/**
 * `forge:jsCoremodRemap` — rewrite source-namespace names inside
 * coremod `.js` resources to the target namespace. FG3-era only:
 * JS coremods are the userdev-era `ICoreMod` script-engine shape
 * (FG1/FG2 coremods are Java classes remapped by the class
 * remapper). Needed for class names under non-mcp dev namespaces
 * (yarn): the csv naming service (1.16.5 `MCPNamingService`)
 * reads methods/fields csvs only — no class channel — so
 * `ASMAPI.mapType`/`mapClass` has nothing to resolve, and
 * literal-embedded names break anyway.
 */
object ForgeJsCoremodRemapStepType : StepType {
    override val id = "forge:jsCoremodRemap"
    override val produces = listOf("forge:jsCoremodRemap")
}

/** Spec for [ForgeJsCoremodRemapStepType]. */
abstract class ForgeJsCoremodRemapStepSpec : PipelineStepSpec {

    override fun transformSteps() = listOf(
        TransformStepContribution(
            RemapSurface.JAR,
            xyz.wagyourtail.unimined.transform.forge.internal
                .CoremodRemapTransformStep,
        )
    )

}

/** ServiceLoader source — forge's step-type feature pack. */
class ForgeStepTypeSource : StepTypeSource {

    override fun stepTypes(): List<StepType> = listOf(
        ForgeBinpatchesStepType,
        ForgeUserdevExecStepType,
        ForgeMergeInputsStepType,
        ForgeSrgRuntimeStepType,
        ForgeJsCoremodRemapStepType,
    )

}
