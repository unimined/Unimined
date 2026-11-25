package xyz.wagyourtail.unimined.remapper.uml

import org.gradle.api.Action
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import xyz.wagyourtail.unimined.remap.RemapSurfaceSpec
import xyz.wagyourtail.unimined.remap.spi.StepSpec

/**
 * Base spec for the Ornithe *metadata-application* steps — `nester`,
 * `raven` and `sparrow` — which apply a feather mapping-extra file
 * (`.nests`, `.excs`, `.sigs`) directly to the artifact's class files,
 * mirroring the standalone Ornithe tools of the same name.
 *
 * The enricher route (`mappings { nests(...) }`) feeds the same data
 * through the remap pipeline; these steps exist for artifacts that are
 * *not* being remapped — pre-built jars, the `decompile` fork after the
 * remap step, or arbitrary jars on a standalone `unimined:remap`
 * pipeline.
 */
abstract class FeatherMetadataStepSpec : StepSpec {
    @get:Input
    @get:Optional
    override val engine: String?
        get() = null

    /** The feather-extra files to apply (`*.nests`/`*.excs`/`*.sigs`). */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val dataFiles: ConfigurableFileCollection

    /** Namespace the file's names are written in — defaults to the
     *  artifact's live pipeline namespace ([PipelineState.currentNamespace]),
     *  which is the right answer when the file was produced for the dev
     *  namespace (Ornithe ships its extras in feather names). */
    @get:Optional
    @get:Input
    abstract val namespace: Property<String>
}

/** Spec for `nester` — applies `.nests` inner-class metadata. */
abstract class NesterStepSpec : FeatherMetadataStepSpec()

/** Spec for `raven` — applies `.excs` exceptions metadata. */
abstract class RavenStepSpec : FeatherMetadataStepSpec()

/** Spec for `sparrow` — applies `.sigs` generic-signature metadata. */
abstract class SparrowStepSpec : FeatherMetadataStepSpec()

/**
 * Spec for `condor` — Ornithe's local-variable-table generator
 * (`net.ornithemc.condor.Condor`, consumed upstream exactly as Ploceus
 * wires it). Methods whose `LocalVariableTable` is missing or incomplete
 * (no `this` entry, or entries not covering `maxLocals`) get one
 * synthesized from classpath-aware frame analysis, with type-derived
 * names, `MethodParameters` name propagation, and ProGuard slot-reuse
 * entries stripped — giving decompilers and Mixin `@Local` captures
 * real variable slots. There is deliberately no `force` knob: upstream
 * always regenerates incomplete tables and never touches complete ones,
 * so neither `force` value had an upstream meaning.
 */
abstract class CondorStepSpec : StepSpec {
    @get:Input
    @get:Optional
    override val engine: String?
        get() = null
}

/**
 * Spec for `preen` — Ornithe's obscure-obfuscation undoer, matching the
 * entry point Ploceus uses. Existing methods recognized as ProGuard-
 * merged bridge/implementation hybrids have `ACC_FINAL`, `ACC_SYNTHETIC`
 * and `ACC_BRIDGE` cleared; no methods are split or synthesized.
 */
abstract class PreenStepSpec : StepSpec {
    @get:Input
    @get:Optional
    override val engine: String?
        get() = null
}

fun RemapSurfaceSpec.nester(
    dataFile: Any, action: Action<NesterStepSpec> = Action {}
): NesterStepSpec = step(NesterStepSpec::class.java) {
    it.dataFiles.from(dataFile); action.execute(it)
}

fun RemapSurfaceSpec.raven(
    dataFile: Any, action: Action<RavenStepSpec> = Action {}
): RavenStepSpec = step(RavenStepSpec::class.java) {
    it.dataFiles.from(dataFile); action.execute(it)
}

fun RemapSurfaceSpec.sparrow(
    dataFile: Any, action: Action<SparrowStepSpec> = Action {}
): SparrowStepSpec = step(SparrowStepSpec::class.java) {
    it.dataFiles.from(dataFile); action.execute(it)
}

fun RemapSurfaceSpec.condor(
    action: Action<CondorStepSpec> = Action {}
): CondorStepSpec = step(CondorStepSpec::class.java, action)

fun RemapSurfaceSpec.preen(
    action: Action<PreenStepSpec> = Action {}
): PreenStepSpec = step(PreenStepSpec::class.java, action)
