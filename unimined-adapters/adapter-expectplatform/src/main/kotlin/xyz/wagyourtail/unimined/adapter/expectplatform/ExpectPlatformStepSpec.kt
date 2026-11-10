package xyz.wagyourtail.unimined.adapter.expectplatform

import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional
import xyz.wagyourtail.unimined.remap.spi.ModArtifactOnlyStepSpec

/**
 * Spec for the `expectPlatform` step: runs ExpectPlatform's
 * `TransformPlatform` statically over the artifact — resolves
 * `@ExpectPlatform` redirects, `@PlatformOnly` filtering, and
 * annotation remaps ([remap], e.g. `@Environment` → the loader's
 * side annotation) into the produced jar.
 *
 * [ModArtifactOnlyStepSpec] — the step is meaningless on the
 * Minecraft artifact; it exists for mod jars, which prod runs
 * (`runProd*`) and shipped artifacts consume without the agent.
 */
abstract class ExpectPlatformStepSpec : ModArtifactOnlyStepSpec {

    @get:Input
    @get:Optional
    override val engine: String?
        get() = null

    /** ExpectPlatform platform name — required. */
    @get:Input
    abstract val platformName: Property<String>

    /** Annotation-descriptor remaps (SimpleRemapper format —
     * `expect.annotation.Environment` → `net.fabricmc.api.Environment`,
     * `expect.annotation.EnvironmentType` → `net.fabricmc.api.EnvType`,
     * enum constants included). */
    @get:Input
    @get:Optional
    abstract val remap: MapProperty<String, String>

    /** Strip ExpectPlatform's own annotations from output. */
    @get:Input
    @get:Optional
    abstract val stripAnnotations: Property<Boolean>

}
