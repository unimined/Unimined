package xyz.wagyourtail.unimined.provider.buildtools

import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import xyz.wagyourtail.unimined.api.spec.LoaderSpec
import xyz.wagyourtail.unimined.api.spec.MinecraftSpec
import xyz.wagyourtail.unimined.minecraft.spi.ArtifactKey
import xyz.wagyourtail.unimined.minecraft.spi.MinecraftArtifacts
import xyz.wagyourtail.unimined.minecraft.spi.MinecraftProvider
import xyz.wagyourtail.unimined.minecraft.spi.ProviderContext
import xyz.wagyourtail.unimined.provider.native.NativeProvider
import xyz.wagyourtail.unimined.provider.native.download.Downloader
import java.time.Duration

/**
 * Produces craftbukkit/spigot server artifacts by delegating vanilla
 * metadata to [NativeProvider] and swapping the [ArtifactKey.SERVER]
 * jar for the output of Spigot's `BuildTools.jar` — run lazily inside
 * the artifacts provider so the expensive git+maven build only fires
 * when the server jar is actually requested.
 *
 * `supports` gates on the loader spec, so a bukkit transform that
 * binds `loader("craftbukkit", "<mc>")` selects this provider through
 * the normal registry path.
 */
class BuildToolsProvider : MinecraftProvider {

    private val delegate = NativeProvider()

    override val name = "buildtools"

    override fun supports(spec: MinecraftSpec): Boolean =
        spec.loader?.name in setOf("craftbukkit", "spigot")

    override fun resolve(spec: MinecraftSpec, ctx: ProviderContext): MinecraftArtifacts {
        val target = spec.loader!!.name
        val rev = spec.version.version
        // vanilla metadata from the native pipeline — strip the loader
        val stripped = object : MinecraftSpec by spec {
            override val loader: LoaderSpec? = null
        }
        val base = delegate.resolve(stripped, ctx)

        val cacheDir = ctx.layout.projectDirectory
            .dir(".gradle/unimined/buildtools-cache").asFile.toPath()
        val downloader = Downloader(
            cacheDir = cacheDir,
            offline = ctx.offline,
            refreshCache = ctx.refreshUniminedDownloads,
            metadataTtl = ctx.metadataCacheTtl ?: Duration.ofHours(24),
            onRefreshFallback = { file, error ->
                ctx.logger.warn(
                    "[Unimined/Cache] Using cached $file after refresh failed: ${error.message}"
                )
            }
        )
        val exec = BuildToolsExec(downloader, cacheDir.resolve(target)) { _, args, dir ->
            ctx.exec.javaexec { java ->
                java.executable = "java"
                java.args = args
                java.workingDir = dir.toFile()
            }
        }

        val serverFile: Provider<RegularFile> = ctx.providers.provider {
            ctx.layout.projectDirectory.file(
                exec.serverJar(target, rev).toAbsolutePath().toString()
            )
        }

        return object : MinecraftArtifacts by base {
            override val artifacts = base.artifacts + mapOf(
                ArtifactKey.SERVER to serverFile,
                ArtifactKey.MERGED to serverFile
            )
        }
    }
}
