package xyz.wagyourtail.unimined.remapper.source

import org.gradle.api.Action
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Optional
import xyz.wagyourtail.unimined.remap.RemapSurfaceSpec
import xyz.wagyourtail.unimined.remap.spi.StepSpec

/**
 * Spec for the `source` remap step — the 1.x
 * `xyz.wagyourtail.unimined:source-remap` (ReplayMod-fork) tool over a
 * sources jar, port of `SourceRemapperImpl`.
 */
abstract class SourceRemapStepSpec : StepSpec {

    /**
     * Resolved classpath of the source-remap tool itself
     * (`java -cp <toolClasspath> com.replaymod.gradle.remap.MainKt ...`).
     * Wire a detached configuration here — e.g.
     * `toolClasspath.from(configurations.detachedConfiguration(dependencies.create("xyz.wagyourtail.unimined:source-remap:…")))`.
     */
    @get:InputFiles
    @get:Classpath
    abstract val toolClasspath: ConfigurableFileCollection

    /** Extra `-cp` entries appended after [StepContext.classpath]. */
    @get:Optional
    @get:InputFiles
    @get:Classpath
    abstract val extraClasspath: ConfigurableFileCollection

    /** Additional CLI args forwarded to the remap tool's main. */
    @get:Input
    abstract val extraArgs: ListProperty<String>

}

/**
 * Kotlin-DSL sugar: `sources { sourceRemap { toolClasspath.from(toolConf) } }`.
 */
fun RemapSurfaceSpec.sourceRemap(action: Action<SourceRemapStepSpec> = Action {}): SourceRemapStepSpec =
    step(SourceRemapStepSpec::class.java, action)
