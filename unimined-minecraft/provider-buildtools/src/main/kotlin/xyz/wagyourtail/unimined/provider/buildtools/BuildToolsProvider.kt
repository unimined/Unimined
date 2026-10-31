package xyz.wagyourtail.unimined.provider.buildtools

import org.gradle.api.GradleException
import org.gradle.api.file.RegularFile
import org.gradle.jvm.toolchain.JavaLanguageVersion
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
import kotlin.io.path.readText

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
        val exec = BuildToolsExec(downloader, cacheDir.resolve(target)) { exe, args, dir ->
            ctx.exec.javaexec { java ->
                java.executable = exe
                java.args = args
                java.workingDir = dir.toFile()
            }
        }

        // BuildTools enforces a per-version java range (versions.json's
        // javaVersions = classfile majors, e.g. [61, 66] -> 17..22) —
        // pick a matching toolchain, or validate the build JVM when
        // useToolchains is off, rather than letting BuildTools abort
        // half an hour into a maven build
        val javaExecutable: Provider<String> = ctx.providers.provider {
            val majors = javaVersionRange(downloader, rev)
            if (majors == null || !ctx.useToolchains.get()) {
                val jvm = Runtime.version().feature()
                if (majors != null && (jvm < majors.first || jvm > majors.last)) {
                    throw GradleException(
                        "$target $rev requires Java " +
                            "${majors.first}-${majors.last}, but the build " +
                            "JVM is $jvm and useToolchains is off"
                    )
                }
                "java"
            } else {
                (majors.first..majors.last).firstNotNullOfOrNull { v ->
                    ctx.javaToolchains.launcherFor { l ->
                        l.languageVersion.set(JavaLanguageVersion.of(v))
                    }.orNull
                }?.executablePath?.asFile?.absolutePath
                    ?: throw GradleException(
                        "no Java toolchain for $target $rev " +
                            "(needs ${majors.first}-${majors.last}) — " +
                            "install one or add a toolchain resolver"
                    )
            }
        }

        val serverFile: Provider<RegularFile> = ctx.providers.provider {
            ctx.layout.projectDirectory.file(
                exec.serverJar(target, rev) { javaExecutable.get() }
                    .toAbsolutePath().toString()
            )
        }

        return object : MinecraftArtifacts by base {
            override val artifacts = base.artifacts + mapOf(
                ArtifactKey.SERVER to serverFile,
                ArtifactKey.MERGED to serverFile
            )
        }
    }

    /** Supported JVM major range for [rev] from `versions/<rev>.json`'s
     * `javaVersions` (classfile majors — 61 = Java 17); null when the
     * field is absent (very old BuildData eras). */
    private fun javaVersionRange(
        downloader: Downloader, rev: String
    ): IntRange? {
        val info = downloader.fetch(
            "https://hub.spigotmc.org/versions/$rev.json",
            "spigot/$rev/build-info.json"
        ).readText()
        return Regex(""""javaVersions"\s*:\s*\[\s*(\d+)\s*,\s*(\d+)\s*]""")
            .find(info)?.let {
                (it.groupValues[1].toInt() - 44)..(it.groupValues[2].toInt() - 44)
            }
    }
}
