package xyz.wagyourtail.unimined.provider.devbundle

import org.gradle.api.GradleException
import org.gradle.api.provider.Provider
import org.gradle.jvm.toolchain.JavaLanguageVersion
import xyz.wagyourtail.unimined.api.spec.ProductSpec
import xyz.wagyourtail.unimined.api.spec.Side
import xyz.wagyourtail.unimined.minecraft.download.Downloader
import xyz.wagyourtail.unimined.minecraft.manifest.ManifestResolver
import xyz.wagyourtail.unimined.minecraft.manifest.VersionJson
import xyz.wagyourtail.unimined.minecraft.spi.ProductArtifacts
import xyz.wagyourtail.unimined.minecraft.spi.ProductProvider
import xyz.wagyourtail.unimined.minecraft.spi.ProviderContext
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.Locale

/**
 * Paperweight dev-bundle provider — `provider("devbundle")`, or
 * negotiated when an env's `legacydist {}` declared source carries
 * `format = "devbundle"`. Claims marker-tagged specs only (priority
 * above `legacydist`'s catch-all claim on declared sources, so the
 * tag wins without an explicit override).
 *
 * The declared zip is materialized into the shared `native-cache`
 * (same root the transform-side producer sees), its `config.json`
 * parsed lazily, and the embedded mojang-mapped paperclip run against
 * the vanilla server download to produce the environment's
 * already-patched server jar.
 */
class DevBundleProvider(
    /** Test seam for the vanilla metadata fetch — production reads
     *  the Mojang manifest via [ManifestResolver]. */
    private val versionJson: (
        resolver: ManifestResolver, minecraftVersion: String
    ) -> VersionJson = { resolver, mc -> resolver.versionJson(mc) }
) : ProductProvider {

    override val name: String = NAME

    /** Marker claims outrank product-family claims (`conversion`'s
     *  product table is 10) — `format = "devbundle"` is an explicit
     *  source-type assertion, not a guess. */
    override val priority: Int = 20

    override fun supports(spec: ProductSpec): Boolean =
        spec.declaredSource?.format == FORMAT

    override fun resolve(spec: ProductSpec, ctx: ProviderContext): ProductArtifacts {
        val declared = spec.declaredSource ?: throw GradleException(
            "devbundle selected without a declaredSource — declare the " +
                    "bundle zip via legacydist { file(...) } or a dist " +
                    "block's file()/url()"
        )
        if (spec.side == Side.CLIENT) throw GradleException(
            "dev bundles are server-only artifacts — set " +
                    "side = Side.SERVER or MERGED"
        )
        // shared with the transform-time producer cacheDir
        // (UniminedCache.global/native-cache) so the paperclip run
        // happens once per bundle whether the classpath view or the
        // raw-artifact transform asks first
        val cacheDir = ctx.globalCacheDir.resolve("native-cache")
        val dir = DevBundleProduction.bundleDir(declared.source, cacheDir)
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
        val resolver = ManifestResolver(
            downloader = downloader,
            metadataTtlOverride = ctx.metadataCacheTtl,
            manifestUrls = listOf(ManifestResolver.VERSION_MANIFEST_V2) +
                    spec.manifestUrls
        )

        val bundleZip: Provider<Path> = ctx.providers.provider {
            DevBundleProduction.materialize(
                declared.source, cacheDir,
                download = downloader::fetch,
                runProducer = { producer, workDir ->
                    ctx.exec.exec { exec ->
                        exec.workingDir = workDir.toFile()
                        exec.executable = producer.executable
                        exec.args(
                            *producer.jvmArgs.toTypedArray(),
                            *producer.args.toTypedArray()
                        )
                    }.rethrowFailure()
                },
                rebuild = ctx.rebuildUniminedArtifacts
            ) { msg -> ctx.logger.warn("[Unimined/devbundle] $msg") }
        }
        val bundle: Provider<DevBundle> = ctx.providers.provider {
            DevBundleProduction.readConfig(bundleZip.get())
        }
        val vanilla: Provider<VersionJson> = ctx.providers.provider {
            versionJson(resolver, bundle.get().minecraftVersion)
        }
        val paperclipJar: Provider<Path> = ctx.providers.provider {
            DevBundleProduction.extractEntry(
                bundleZip.get(), bundle.get().mojangMappedPaperclipFile,
                dir.resolve("paperclip.jar")
            )!!
        }

        val patchedJar: Provider<Path> = ctx.providers.provider {
            val cfg = bundle.get()
            if (cfg.minecraftVersion != spec.version.version) {
                ctx.logger.warn(
                    "[Unimined/devbundle] bundle minecraftVersion " +
                            "${cfg.minecraftVersion} differs from the " +
                            "environment's declared version " +
                            "${spec.version.version} — the bundle wins"
                )
            }
            val version = vanilla.get()
            val ref = version.downloads?.get("server")
                ?: throw GradleException(
                    "Minecraft ${cfg.minecraftVersion} has no server " +
                            "jar download"
                )
            val vanillaJar = downloader.fetch(
                ref.url,
                "versions/${cfg.minecraftVersion}/server.jar",
                ref.sha1?.lowercase(Locale.ROOT)
            )
            DevBundleProduction.produceDevJar(
                bundleZip.get(), cfg, dir, vanillaJar,
                javaExecutable = javaExecutableFor(
                    ctx, version.javaVersion?.majorVersion ?: 8
                ),
                runJava = { inv ->
                    // the stream must stay open across the javaexec —
                    // it writes *after* the config lambda returns, so a
                    // `use` inside it would hand the process a closed
                    // stream
                    Files.newOutputStream(inv.logFile).use { log ->
                        ctx.exec.javaexec { java ->
                            inv.executable?.let { java.executable = it }
                            java.classpath = ctx.objects.fileCollection()
                                .from(inv.classpath.map { it.toFile() })
                            java.mainClass.set(inv.mainClass)
                            java.jvmArgs = inv.jvmArgs
                            java.args = inv.args
                            java.workingDir = inv.workingDir.toFile()
                            java.standardOutput = log
                            java.errorOutput = log
                        }.rethrowFailure()
                    }
                },
                rebuild = ctx.rebuildUniminedArtifacts
            )
        }

        return DevBundleArtifacts(
            spec, ctx, bundleZip, bundle, vanilla, paperclipJar,
            patchedJar, dir, downloader
        )
    }

    /** A `java` executable at [required] or newer — matching
     *  toolchain when `useToolchains` resolves one, else the build
     *  JVM if it qualifies. */
    private fun javaExecutableFor(ctx: ProviderContext, required: Int): String {
        if (ctx.useToolchains.get()) {
            (required..required + 8).firstNotNullOfOrNull { v ->
                ctx.javaToolchains.launcherFor { l ->
                    l.languageVersion.set(JavaLanguageVersion.of(v))
                }.orNull
            }?.let { return it.executablePath.asFile.absolutePath }
        }
        val jvm = Runtime.version().feature()
        if (jvm >= required) return "java"
        throw GradleException(
            "devbundle requires Java $required+ but the build JVM is " +
                    "$jvm and no qualifying toolchain was found — " +
                    "install one or add a toolchain resolver"
        )
    }

    companion object {
        const val NAME = "devbundle"

        /** `legacydist {}` `format` value marking a declared source as
         *  a paperweight dev bundle — the auto-negotiation tag. */
        const val FORMAT = "devbundle"
    }

}
