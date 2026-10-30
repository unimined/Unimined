package xyz.wagyourtail.unimined.minecraft.spi

import xyz.wagyourtail.commons.core.logger.Logger
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

    /** Resolved `minecraftLibraries` jars — the merge strips server-side
     *  copies of bundled entries (pre-1.13 server jars embed their
     *  runtime libraries inline). */
    val libraryJars: List<Path> = emptyList(),

    /** Merge keep-overrides — entry globs never stripped. */
    val mergeIncludeGlobs: List<String> = emptyList(),

    /** Extra entry globs stripped from the *server* jar during merge. */
    val mergeExcludeGlobs: List<String> = emptyList(),

    /** Master switch for the server-bundled-library strip. */
    val stripBundledServerLibraries: Boolean = true,

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

    /**
     * Output-format version of this producer's pipeline. Wired into the
     * artifact transform's inputs, so a format change (unbundling, merge
     * filtering, …) invalidates cached transform outputs — bump it
     * whenever produced-artifact content changes for identical inputs.
     */
    val artifactFormatVersion: Int get() = 0

    /** Produce [request]'s artifact for [spec]; returns the jar path. */
    fun produce(spec: MinecraftSpec, request: ArtifactProductionRequest): Path

}
