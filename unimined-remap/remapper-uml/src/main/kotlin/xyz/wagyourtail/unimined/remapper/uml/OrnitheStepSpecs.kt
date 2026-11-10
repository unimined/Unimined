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
 * Spec for `condor` — Ornithe's local-variable-table generator. Methods
 * lacking a `LocalVariableTable` get one synthesized from frame analysis,
 * giving decompilers and Mixin `@Local` captures real variable slots.
 */
abstract class CondorStepSpec : StepSpec {
    @get:Input
    @get:Optional
    override val engine: String?
        get() = null

    /** Regenerate even when a (partial) table is present. Default off:
     *  existing debug info is kept. */
    @get:Input
    abstract val force: Property<Boolean>
}

/**
 * Spec for `preen` — Ornithe's obscure-obfuscation undoer. Splits
 * ProGuard-merged bridge methods back out: when call sites invoke
 * `name(desc)` but only `name(otherDesc)` is declared, a synthetic
 * `ACC_BRIDGE` delegate is synthesized so the bytecode shape matches
 * what javac would have emitted.
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
): CondorStepSpec = step(CondorStepSpec::class.java) {
    it.force.set(false); action.execute(it)
}

fun RemapSurfaceSpec.preen(
    action: Action<PreenStepSpec> = Action {}
): PreenStepSpec = step(PreenStepSpec::class.java, action)
