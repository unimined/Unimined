package xyz.wagyourtail.unimined.transform

import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import xyz.wagyourtail.unimined.remap.spi.DevArtifactOnlyStepSpec

/**
 * Spec for the `jar-processors` step — runs every environment-declared
 * [xyz.wagyourtail.unimined.transform.processor.JarProcessor] spec over
 * the dev-Minecraft artifact, in declaration order (Loom's
 * `MinecraftJarProcessorManager` stage).
 *
 * [processorSpecs] holds each active processor's serialized spec as
 * `name:payload` entries — built lazily at fingerprint/execution time
 * over the mod batch, so spec changes invalidate the processed jar.
 *
 * [DevArtifactOnlyStepSpec]: jar processors mutate Minecraft itself —
 * running them over a production jar or a mod dependency is
 * meaningless.
 */
abstract class JarProcessorsStepSpec : DevArtifactOnlyStepSpec {

    /**
     * `name:payload` entries — one per processor whose `buildSpec`
     * returned non-null. Populated by the environment wiring.
     */
    @get:Input
    abstract val processorSpecs: ListProperty<String>

    /**
     * The resolved mod batch processors' specs were built from —
     * fingerprinted so a mod-content change invalidates the artifact
     * even when the serialized specs happen not to change.
     */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val modJars: ConfigurableFileCollection

}
