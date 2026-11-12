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
    if (resolved.descriptor.id != "forge") return null
    val profile = resolved.profile as? ForgeProfile.Userdev
        ?: return null
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
     *  carries no loader classes. */
    val markerJar: Path = jar
)

/**
 * Resolve + classify the loader artifact lazily. Probe order
 * (plan §5 era axis):
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
internal fun contributeLoaderResolution(
    project: Project,
    environment: MinecraftEnvironment
): Provider<ResolvedForge> = project.provider {
    val spec = environment.loaderSpec.orNull
        ?: return@provider null
    val descriptor = LoaderRegistry.resolveBuiltin(spec.name)
        ?.takeIf { it.family == "forge" }
        ?: return@provider null
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
                        xyz.wagyourtail.unimined.api.spec.Side.MERGED ->
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
        ?: error(
            "Unable to resolve forge loader artifact at $base " +
                    "(:userdev/:universal classifiers, bare artifact, " +
                    "and sided @zip forms all failed)"
        )
    ResolvedForge(
        descriptor, ForgeInstallerProbe.inspect(jar), notation!!, jar,
        mergeJars ?: listOf(jar), markerJar ?: jar
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
