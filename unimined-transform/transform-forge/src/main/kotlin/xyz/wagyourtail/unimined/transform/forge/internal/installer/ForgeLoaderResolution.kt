package xyz.wagyourtail.unimined.transform.forge.internal.installer

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.gradle.api.Project
import org.gradle.api.artifacts.type.ArtifactTypeDefinition
import org.gradle.api.file.RegularFile
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import xyz.wagyourtail.unimined.api.access.AccessModel
import xyz.wagyourtail.unimined.api.spec.LoaderSpec
import xyz.wagyourtail.unimined.api.spec.Side
import xyz.wagyourtail.unimined.api.util.failProblem
import xyz.wagyourtail.unimined.mapping.MappingsSpecImpl
import xyz.wagyourtail.unimined.mapping.sink.emit
import xyz.wagyourtail.unimined.minecraft.MinecraftEnvironment
import xyz.wagyourtail.unimined.minecraft.MinecraftExtension
import xyz.wagyourtail.unimined.remap.AccessRemapStepSpec
import xyz.wagyourtail.unimined.remap.RemapExtension
import xyz.wagyourtail.unimined.remap.spi.RemapSurface
import xyz.wagyourtail.unimined.remap.spi.StepScope
import xyz.wagyourtail.unimined.remapper.access.ApplyClassTweakerStepSpec
import xyz.wagyourtail.unimined.remapper.access.ClassTweaker
import xyz.wagyourtail.unimined.remapper.jst.JstStepSpec
import xyz.wagyourtail.unimined.transform.JarProcessorsStepSpec

import xyz.wagyourtail.unimined.transform.forge.internal.mcpconfig.McpConfigRunner
import xyz.wagyourtail.unimined.transform.forge.processor.UserdevInjectProcessor
import xyz.wagyourtail.unimined.transform.forge.processor.UserdevUniversalShadeProcessor
import xyz.wagyourtail.unimined.transform.loader.LoaderDescriptor
import xyz.wagyourtail.unimined.transform.loader.LoaderRegistry
import java.io.File
import java.nio.file.Path
import kotlin.io.path.exists
import xyz.wagyourtail.unimined.transform.forge.internal.installer.ForgeInstallerProbe
import xyz.wagyourtail.unimined.transform.forge.internal.userdev.UserdevExtract
import xyz.wagyourtail.unimined.transform.forge.internal.ForgeProfile

/**
 * Loader-coordinate resolution and version probes. Turns the user's
 * `loader("forge", version)` notation into a [ResolvedForge] — descriptor
 * + probed [ForgeProfile] + jar path — via [ForgeInstallerProbe] (the jar
 * tells us which forge era we're on: userdev json, binpatches pack, or
 * FG2-era merged). Also hosts the small version/predicate helpers the run
 * mutators and dev-bootstrap logic share.
 */
internal fun bootstrapDevVersion(resolved: ResolvedForge): String? {
    val profile = resolved.profile as? ForgeProfile.Userdev
        ?: return null
    // Product-shape detection, not product-id detection: lexforge is a
    // distinct selector for the same net.minecraftforge userdev artifacts,
    // so it needs the same bootstrap-dev companion as the `forge` id.
    // NeoForge/cleanroom naturally miss this coordinate-shaped probe unless
    // their own userdev metadata really declares net.minecraftforge:bootstrap.
    return UserdevExtract.libraryCoords(profile.config)
        .firstNotNullOfOrNull { coord ->
            val parts = coord.split(":")
            if (parts.size >= 3 && parts[0] == "net.minecraftforge" &&
                parts[1] == "bootstrap"
            ) parts[2] else null
        }
}

/**
 * The probed loader: product descriptor + packaging-era profile +
 * the artifact the profile was derived from.
 */
internal class ResolvedForge(
    val descriptor: LoaderDescriptor,
    val profile: ForgeProfile,
    /** The resolved `g:a:v[:classifier]` coordinate — era-correct
     *  classifier for classpath deps (bare `g:a:v` jars don't
     *  exist for FG2-era Forge — only `-universal`/`-userdev`). */
    val notation: String,
    val jar: Path,
    /** `jarmod:merge` inputs — `[jar]` except sided FG1 forms on a
     *  MERGED env, where the client and server zips both merge. */
    val mergeJars: List<Path> = listOf(jar),
    /** The jar `SideMarker.detectSideMarker` scans — the *runtime*
     *  forge artifact shipping the `SideOnly`/`OnlyIn` annotation
     *  (1.x `actualSideMarker` read `forge.dependencies.last()`).
     *  Defaults to [jar]; the FG2 `userdev3` dev package overrides it
     *  with the universal jar it was resolved alongside — userdev3
     *  carries no loader classes. The FG2 `userdev` dev package does
     *  the same via [universalJar]. */
    val markerJar: Path = jar,
    /** The `universal` artifact resolved alongside an FG2 `userdev`
     *  dev package — the canonical `binpatches.pack.lzma` carrier.
     *  The package's own `devbinpatches.pack` is authored against
     *  FML's workspace merge (MC classes recompiled with the
     *  `forgepatches` source patches applied — its input checksums
     *  never match a canonical merged jar), so it is only a fallback
     *  when no universal resolves. `null` outside that case. */
    val universalJar: Path? = null
)

/** Resolve + classify the primary loader artifact lazily — see
 *  [resolveForgeLoader] for the probe cascade. */
internal fun contributeLoaderResolution(
    project: Project,
    environment: MinecraftEnvironment
): Provider<ResolvedForge> {
    // Compute once: the provider is consumed inside deferred
    // pipeline/step providers that can materialize in
    // resolution-restricted contexts (transform-param isolation,
    // `withDependencies` hooks, task-input fingerprinting), where a
    // fresh detached resolution throws "attempted without an
    // exclusive lock". The first evaluation is forced at config
    // time (ForgeEnvironmentWiring's afterDslEval hooks) — every
    // later read reuses this value.
    var computed = false
    var cache: ResolvedForge? = null
    return project.provider {
        if (!computed) {
            val spec = environment.loaderSpec.orNull
            val descriptor = spec?.let {
                LoaderRegistry.resolveBuiltin(it.name)
            }?.takeIf { it.family == "forge" }
            if (spec != null && descriptor != null) {
                // commit the cache only once the resolution actually
                // runs — this provider is read while the env DSL is
                // still configuring (probeResolver/envArtifactBindings
                // during binding, before `forge()` sets loaderSpec) and
                // in resolution-restricted contexts where a detached
                // resolve can throw; an early/throwing read must stay
                // retryable or the transient null/failure poisons every
                // convention keyed off this provider permanently
                cache = resolveForgeLoader(
                    project, environment, spec, descriptor)
                computed = true
            }
        }
        cache
    }
}

/**
 * Probe one declared loader spec's artifact — the era cascade +
 * [ForgeInstallerProbe] shared by the primary
 * ([contributeLoaderResolution]) and merged-env secondary
 * contributions (`forgeMergedLoader<Env>`).
 *
 * Probe order (plan §5 era axis):
 * 1. `$base:userdev` — FG3+ `config.json`, or the FG2 `dev.json`
 *    dev-package (becomes `Binpatches.devPackage`)
 * 2. `$base:universal` / `$base:universal@zip` — FG2 binpatches /
 *    FG1 jar patches (FG1 universal ships as `.zip`)
 * 3. `$base` bare / `$base@zip` — bare-artifact forms
 * 4. sided FG1-era zips — pre-1.3 ships only `:$side@zip`
 *    (`client`/`server`, env-side picked, both on MERGED); the
 *    `jarmod:merge` input mirrors jarmod's own fg1 logic
 *
 * Resolution failures on a candidate fall through to the next —
 * the `userdev`/`universal` classifiers don't exist for all
 * versions.
 */
internal fun resolveForgeLoader(
    project: Project,
    environment: MinecraftEnvironment,
    spec: LoaderSpec,
    descriptor: LoaderDescriptor
): ResolvedForge {
    val mc = environment.version.get()
    val base = descriptor.loaderCoords(mc, spec.version)

    fun tryResolve(notation: String): Path? = runCatching {
        project.configurations.detachedConfiguration(
            project.dependencies.create(notation)
        ).apply { isTransitive = false }.files.single().toPath()
    }.getOrNull()

    var notation: String? = null
    var mergeJars: List<Path>? = null
    var markerJar: Path? = null
    val jar = tryResolve("$base:userdev")?.also { notation = "$base:userdev" }
        ?: run {
            val universal = tryResolve("$base:universal")
                ?.also { notation = "$base:universal" }
                // FG1-era universal ships as `.zip` (pom-less dir feed)
                ?: tryResolve("$base:universal@zip")
                    ?.also { notation = "$base:universal@zip" }
                ?: tryResolve(base)?.also { notation = base }
                ?: tryResolve("$base@zip")?.also { notation = "$base@zip" }
                ?: run {
                    // pre-1.3 FG1 — only per-side `:$side@zip` zips
                    // exist; MERGED merges client+server like the
                    // base MC merge (jarmod's fg1 classifier logic)
                    val minor = Regex("^1\\.(\\d+)").find(mc)
                        ?.groupValues?.get(1)?.toIntOrNull()
                    if (minor == null || minor >= 3) return@run null
                    val classifiers = when (environment.side.get()) {
                        xyz.wagyourtail.unimined.api.spec.Side.SERVER ->
                            listOf("server")
                        // common envs carry the joined jar — both
                        // sided zips merge onto it
                        xyz.wagyourtail.unimined.api.spec.Side.MERGED,
                        xyz.wagyourtail.unimined.api.spec.Side.COMMON ->
                            listOf("client", "server")
                        else -> listOf("client")
                    }
                    val jars = classifiers.mapNotNull {
                        tryResolve("$base:$it@zip")
                    }
                    if (jars.size != classifiers.size) return@run null
                    notation = "$base:${classifiers.first()}@zip"
                    mergeJars = jars
                    jars.first()
                }
            // 1.x `userdevClassifier`: a universal carrying
            // `binpatches.pack.lzma` pairs with the `userdev3`
            // classifier (FG3-era config.json dev metadata
            // backported to FG2-era MC, e.g. 1.12.2/14.23.x)
            if (universal != null &&
                ForgeInstallerProbe.hasEntry(universal, "binpatches.pack.lzma")
            ) {
                tryResolve("$base:userdev3")
                    ?.also {
                        notation = "$base:userdev3"
                        // the marker annotation ships in the runtime
                        // universal jar, not the dev package
                        markerJar = universal
                    } ?: universal
            } else universal
        }
        ?: project.failProblem(
            "forge/loader-resolve",
            "unimined: environment " +
                "'${environment.environmentId}' — unable to resolve " +
                "forge loader artifact at $base",
            ":userdev/:universal classifiers, bare artifact, and sided " +
                "@zip forms all failed — check the coordinate, the " +
                "serving repository, and whether you are offline"
        )
    val profile = ForgeInstallerProbe.inspect(jar)
    // FG2 `userdev` dev package — resolve the `universal` alongside
    // it: the package ships metadata only (no runtime classes for
    // marker detection) and its `devbinpatches` input checksums
    // target FML's source-patched workspace merge, not the canonical
    // merged jar
    val universal = if ((profile as? ForgeProfile.Binpatches)
            ?.devPackage != null
    ) tryResolve("$base:universal") else null
    return ResolvedForge(
        descriptor, profile, notation!!, jar,
        mergeJars ?: listOf(jar),
        markerJar = markerJar ?: universal ?: jar,
        universalJar = universal
    )
}

/**
 * Contribute the JAR-surface steps + analysis overlay for
 * access-transformers: the environment's declared
 * [MinecraftEnvironment.accessTransformers] plus every AT extracted
 * out of compile-classpath mod dependencies (FML applies dep ATs
 * unconditionally — there is no transitive subset, so extracted
 * files join [ApplyClassTweakerStepSpec.classTweakerFiles], not the
 * transitive-filtered dependency slot), and `remap-access` rebases
 * AT resources embedded in mod jars onto the dev namespace.
 */
internal fun detectTweakClass(jar: Path, side: String): String? {
    val simple = if (side == "server") "FMLServerTweaker" else "FMLTweaker"
    val cpwEntry = "cpw/mods/fml/common/launcher/$simple.class"
    val forgeEntry = "net/minecraftforge/fml/common/launcher/$simple.class"
    java.util.zip.ZipFile(jar.toFile()).use { zip ->
        return when {
            zip.getEntry(cpwEntry) != null ->
                "cpw.mods.fml.common.launcher.$simple"

            zip.getEntry(forgeEntry) != null ->
                "net.minecraftforge.fml.common.launcher.$simple"

            else -> null
        }
    }
}

/** `bootstrap-dev <2.1.4` splits `source_roots` on `;` literally. */
internal fun bootstrapDevNeedsSemicolon(version: String): Boolean {
    val parts = version.split('.').mapNotNull { it.toIntOrNull() }
    return parts.isNotEmpty() && (parts[0] < 2 ||
            (parts[0] == 2 && (parts.getOrElse(1) { 0 } < 1) ||
                    (parts[0] == 2 && parts.getOrElse(1) { 0 } == 1 &&
                            parts.getOrElse(2) { 0 } < 4)))
}

/**
 * 1.x `srgToMCPAsSRG`: the environment mapping tree exported to
 * `prodNs -> devNs` srg — `GradleStart` feeds it to FML's runtime
 * remapper for classpath mods.
 */
