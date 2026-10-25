package xyz.wagyourtail.unimined.minecraft.spi

import org.gradle.api.logging.Logger
import xyz.wagyourtail.unimined.api.spec.MinecraftSpec
import xyz.wagyourtail.unimined.api.spi.Supports
import java.nio.file.Path
import java.time.Duration

/**
 * What an [MinecraftArtifactProducer] must produce: the environment's raw
 * (source-namespace) Minecraft artifact inside an artifact-transform
 * execution — no `Project`, no Gradle services.
 */
class ArtifactProductionRequest(
    /** Provider cache root — downloads/merges land here. */
    val cacheDir: Path,

    /** Which artifact to produce. */
    val artifactKey: ArtifactKey,

    val offline: Boolean = false,

    val refreshUniminedDownloads: Boolean = false,

    val rebuildUniminedArtifacts: Boolean = false,

    val metadataCacheTtl: Duration? = null,

    val logger: Logger
)

/**
 * Artifact-transform-safe counterpart to [MinecraftProvider]: produces one
 * raw environment artifact on disk without Gradle services, so it can run
 * inside a `TransformAction` (worker-isolated, no `ExecOperations`).
 *
 * Producers self-register via
 * `META-INF/services/xyz.wagyourtail.unimined.minecraft.spi.MinecraftArtifactProducer`
 * and are rediscovered at transform execution time — impls never live in
 * serialized transform state.
 *
 * Providers that fundamentally need a subprocess (mavenizer/NFRT CLIs,
 * forge installers) cannot implement this; their artifacts take the
 * eager task + local-repo publish route instead.
 */
interface MinecraftArtifactProducer : Supports<MinecraftSpec> {

    /** Produce [request]'s artifact for [spec]; returns the jar path. */
    fun produce(spec: MinecraftSpec, request: ArtifactProductionRequest): Path

}
