package xyz.wagyourtail.unimined.provider.legacydist

import org.gradle.api.GradleException
import xyz.wagyourtail.unimined.api.spec.Side
import xyz.wagyourtail.unimined.api.spec.ProductSpec
import xyz.wagyourtail.unimined.minecraft.download.Downloader
import xyz.wagyourtail.unimined.minecraft.spi.ProductArtifacts
import xyz.wagyourtail.unimined.minecraft.spi.ProductProvider
import xyz.wagyourtail.unimined.minecraft.spi.ProviderContext
import java.time.Duration

/**
 * Declared-source provider — `provider("legacydist")`, or implicitly
 * when an env declares `legacydist {}`. Claims any spec carrying a
 * `declaredSource`; materializes it (local file / URL+SHA-256 /
 * external producer) into a content-addressed cache under the shared
 * `native-cache` root — the same dir [LegacyDistArtifactProducer]
 * resolves inside the raw-artifact transform, so the jar is produced
 * once whichever path asks first. No manifest is consulted — works for
 * non-MC-version jars (BTA/ReIndev, pre-1.8 bukkit, air-gapped
 * artifacts).
 *
 * Maven-coordinate sources are materialized to a local file by the
 * environment DSL before provider resolution (`legacydist`'s
 * `declaration()` resolves them eagerly) — providers have no
 * configuration access.
 */
class LegacyDistProvider : ProductProvider {

    override val name: String = "legacydist"

    /** Above `native`'s loader-gated catch-all (0) — a declared source
     *  is explicit intent, so `custom { file() }` envs without a
     *  loader negotiate here rather than colliding at the floor —
     *  below `conversion`'s product table (10). Mirrors the
     *  producer-side ladder. */
    override val priority: Int = 5

    override fun supports(spec: ProductSpec): Boolean =
        spec.declaredSource != null

    override fun resolve(spec: ProductSpec, ctx: ProviderContext): ProductArtifacts {
        val declared = spec.declaredSource
            ?: throw GradleException("legacydist selected without a declaredSource")
        // shared with the transform-time producer cacheDir
        // (UniminedCache.global/native-cache) — project-local roots made
        // every download run twice per cold build
        val cacheDir = ctx.globalCacheDir.resolve("native-cache")
        val downloader = Downloader(
            cacheDir = cacheDir,
            offline = ctx.offline,
            refreshCache = ctx.refreshUniminedDownloads,
            metadataTtl = ctx.metadataCacheTtl ?: Duration.ofHours(24),
            onRefreshFallback = { file, error ->
                ctx.logger.warn(
                    "[Unimined/Cache] Using cached $file after refresh " +
                            "failed: ${error.message}"
                )
            }
        )
        val jar = ctx.providers.provider {
            try {
                LegacyDistProduction.materialize(
                    declared.source,
                    "${spec.environmentId}-${spec.version.version}.jar",
                    cacheDir,
                    download = downloader::fetch,
                    runProducer = { source, workDir ->
                        ctx.exec.exec { exec ->
                            exec.workingDir = workDir.toFile()
                            exec.executable = source.executable
                            exec.args(
                                *source.jvmArgs.toTypedArray(),
                                *source.args.toTypedArray()
                            )
                        }.rethrowFailure()
                    },
                    rebuild = ctx.rebuildUniminedArtifacts,
                    projectDir = ctx.layout.projectDirectory.asFile.toPath(),
                    warn = { ctx.logger.warn("[Unimined/legacydist] $it") }
                ).toFile()
            } catch (e: IllegalStateException) {
                // provider-side failures read as DSL errors, matching
                // the pre-production-object behavior
                throw GradleException(
                    e.message ?: "legacydist materialization failed", e
                )
            }
        }
        return LegacyDistArtifacts(spec, declared, jar, ctx)
    }

}

internal fun Side.artifactKey() = when (this) {
    Side.CLIENT -> xyz.wagyourtail.unimined.minecraft.spi.ArtifactKey.CLIENT
    Side.SERVER -> xyz.wagyourtail.unimined.minecraft.spi.ArtifactKey.SERVER
    Side.MERGED -> xyz.wagyourtail.unimined.minecraft.spi.ArtifactKey.MERGED
    // a declared dist is a single unified jar — "common" aliases it
    Side.COMMON -> xyz.wagyourtail.unimined.minecraft.spi.ArtifactKey.COMMON
}
