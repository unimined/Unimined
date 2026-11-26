package xyz.wagyourtail.unimined.transform.forge.internal.binpatch

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
import xyz.wagyourtail.unimined.transform.forge.internal.userdev.UserdevExtract
import xyz.wagyourtail.unimined.transform.forge.processor.UserdevInjectProcessor
import xyz.wagyourtail.unimined.transform.forge.processor.UserdevUniversalShadeProcessor
import xyz.wagyourtail.unimined.transform.loader.LoaderDescriptor
import xyz.wagyourtail.unimined.transform.loader.LoaderRegistry
import java.io.File
import java.nio.file.Path
import kotlin.io.path.exists
import xyz.wagyourtail.unimined.transform.forge.internal.binpatch.BinpatchesPackStepSpec
import xyz.wagyourtail.unimined.transform.forge.internal.fg2.FG2DevFixesStepSpec
import xyz.wagyourtail.unimined.transform.forge.internal.mcpconfigCleanJar
import xyz.wagyourtail.unimined.transform.forge.internal.installer.ResolvedForge
import xyz.wagyourtail.unimined.transform.forge.internal.userdev.UserdevBinpatchStepSpec
import xyz.wagyourtail.unimined.transform.forge.internal.ForgeProfile
import xyz.wagyourtail.unimined.transform.forge.internal.contributeAccessSteps

/**
 * The jar holding the binpatch pack for this environment — the
 * userdev jar itself for joined envs (`config.binpatches` →
 * `joined.lzma` ships inside userdev; COMMON rides the joined chain
 * too), the `:installer` artifact for split-side envs
 * (`data/<side>.lzma` — 1.x read the same). Null for non-Userdev
 * profiles.
 */
internal fun userdevInstallerJar(
    project: Project,
    environment: MinecraftEnvironment,
    resolved: Provider<ResolvedForge>
): Provider<File> = resolved.map { r ->
    val profile = (r.profile as? ForgeProfile.Userdev) ?: return@map null
    when {
        // combined packs live in the userdev artifact itself — the
        // installer's client-side pack is not the dev pipeline input
        profile.config.combinedBinaryPatches -> r.jar.toFile()
        environment.side.get() == Side.MERGED ||
                environment.side.get() == Side.COMMON -> r.jar.toFile()
        else -> {
            val base = r.descriptor.loaderCoords(
                environment.version.get(),
                environment.loaderSpec.get().version
            )
            project.configurations.detachedConfiguration(
                project.dependencies.create("$base:installer")
            ).apply { isTransitive = false }.files.single()
        }
    }
}

/** Entry inside [userdevInstallerJar]'s artifact carrying the patch
 *  pack for this environment. Combined profiles always name their
 *  userdev-local joined pack (`patches.lzma` on NeoForge 1.21.11+);
 *  older split-side profiles use the installer's `data/<side>.lzma`. */
internal fun userdevBinpatchesKey(
    profile: ForgeProfile.Userdev,
    side: Side
): String? = when {
    profile.config.combinedBinaryPatches -> profile.config.binpatches
    side == Side.MERGED || side == Side.COMMON -> profile.config.binpatches
    side == Side.CLIENT -> "data/client.lzma"
    side == Side.SERVER -> "data/server.lzma"
    else -> profile.config.binpatches
}

/**
 * The installer-chain `rename` tool coordinate for split-side envs —
 * consumed by `mcpconfigCleanJar`'s
 * [McpConfigTransform.Params.renameToolOverride]. Split-side
 * `data/<side>.lzma` packs were generated against the *installer*
 * pipeline's renamed jar (bundler-extract → jarsplitter slim →
 * `ForgeAutoRenamingTool`), whose tool version is declared in
 * `install_profile.json`, not in mcpconfig's `functions.rename`
 * (47.3.0: FART 0.1.22 vs 1.0.2 emit byte-divergent output on
 * identical input → every patch checksum fails). Absent on joined
 * envs (the `joined.lzma` pack tracks the descriptor chain), on
 * non-Userdev/`notchObf` profiles, and when the installer profile
 * declares no FART processor (mcpconfig-declared tool is used then).
 */
internal fun installerRenameToolOverride(
    project: Project,
    environment: MinecraftEnvironment,
    resolved: Provider<ResolvedForge>
): Provider<String> = project.provider {
    when (environment.side.get()) {
        Side.MERGED, Side.COMMON -> return@provider null
        else -> {}
    }
    if ((resolved.orNull?.profile as? ForgeProfile.Userdev)
            ?.config?.notchObf != false) return@provider null
    userdevInstallerJar(project, environment, resolved).orNull
        ?.let { jar ->
            UserdevExtract.installerToolCoord(
                jar.toPath(), "net.minecraftforge", "ForgeAutoRenamingTool"
            )
        }
}

/**
 * Patch-step specs. `contributeBinpatchStep` wires `userdev-binpatch`
 * (GDIFF patches from the userdev profile) and `binpatches-pack` onto the
 * dev/modsource surfaces; `contributeDevFixes` adds the FG2 dev fixes
 * (unprotect, FMLTweaker cleanup) for the old-era path.
 */
internal fun contributeBinpatchStep(
    project: Project,
    environment: MinecraftEnvironment,
    resolved: Provider<ResolvedForge>
) {
    val objects: ObjectFactory = project.objects
    val remap = project.extensions.getByType(RemapExtension::class.java)
    val environmentSpec = remap.sourceSet(environment.environmentId)

    val spec = objects.newInstance(UserdevBinpatchStepSpec::class.java)
    spec.installerJar.set(project.layout.file(
        userdevInstallerJar(project, environment, resolved)
    ))
    spec.binpatchesKey.set(resolved.map {
        val p = it.profile as? ForgeProfile.Userdev ?: return@map null
        userdevBinpatchesKey(p, environment.side.get())
    })
    // in-process patcher — the userdev `binpatcher` tool spec
    // (binarypatcher JVM + args) is not consulted

    // post-remap: FG's binpatches index the srg-renamed clean jar
    // (`net.minecraft:joined:<ver>:srg` — FG6 MinecraftUserRepo) —
    // the mcpconfig `rename`/`merge` output, produced lazily here.
    // Byte-identical to what forge's checksums were generated
    // against; a tiny-remap of the same input is not.
    spec.cleanJar.set(project.layout.file(
        mcpconfigCleanJar(project, environment, resolved)
    ))
    // patch steps are artifact-scoped — dependency jars routed
    // through the environment pipeline must not be binpatched.
    // Pre-remap for every era: the step substitutes the patched
    // clean jar wholesale, so the flowing content sits at the
    // profile's `obfNamespace` boundary (srg for modern userdev,
    // obf for `notchObf`-era records) before the core rename —
    // the `addExtraInnerClassMappings` hook feeds forge-added
    // classes into the tree that rename consumes.
    environmentSpec.contributePreStep(
        RemapSurface.JAR, spec, StepScope.ENVIRONMENT_ARTIFACT
    )

    // FG2-era binary patching — `Binpatches` profile: reads the
    // pack out of the universal jar, or `devbinpatches.pack.lzma`
    // from a FG2 `userdev` dev package when one exists (1.x only
    // knew the universal path; plan §5). In-process patcher —
    // no tool exec.
    val packSpec = objects.newInstance(BinpatchesPackStepSpec::class.java)
    packSpec.loaderJar.set(project.layout.file(resolved.map { r ->
        val p = r.profile as? ForgeProfile.Binpatches ?: return@map null
        (p.devPackage?.jar ?: r.jar).toFile()
    }))
    packSpec.packEntry.set(resolved.map { r ->
        val p = r.profile as? ForgeProfile.Binpatches ?: return@map null
        if (p.devPackage != null) "devbinpatches.pack.lzma"
        else "binpatches.pack.lzma"
    })
    packSpec.side.set(resolved.map { r ->
        if (r.profile !is ForgeProfile.Binpatches) return@map null
        when (environment.side.get()) {
            Side.CLIENT -> "client"
            Side.SERVER -> "server"
            // the common (intersection) env rides the joined patch
            // chain — userdev has no per-side common descriptor
            Side.MERGED, Side.COMMON -> "joined"
        }
    })
    val absent = project.providers.provider<RegularFile> { null }
    val sideJars = project.extensions.getByType(MinecraftExtension::class.java)
        .artifacts(environment.environmentId).map { it.auxiliaryArtifacts }

    fun sideJar(key: String): Provider<RegularFile> = resolved.flatMap { r ->
        if (r.profile !is ForgeProfile.Binpatches) absent
        else sideJars.flatMap { it[key] ?: absent }
    }
    packSpec.clientJar.set(sideJar("client_jar"))
    packSpec.serverJar.set(sideJar("server_jar"))
    environmentSpec.contributePreStep(
        RemapSurface.JAR, packSpec, StepScope.ENVIRONMENT_ARTIFACT
    )
}

/**
 * FG2 dev-environment injections — classpath coremods,
 * MOD_CLASSES resource/lang loading, runtime-deobf strip +
 * `UniminedFg2` helper splice. Applies to every FG2-era
 * environment: the `Binpatches` profile AND `Userdev` with
 * `notchObf` (userdev3 backported to FG2-era MC, e.g. 1.12.2 —
 * 1.x applies `FixFG2Coremods`/`ResourceLoading`/
 * `DeobfEnvironment` on the FG3 path whenever the version is
 * FG2-era). Contributed after `contributeAccessSteps` so it runs
 * after `jarProcessors` — it patches the FML classes that only
 * exist in-jar once the universal shade lands.
 */
internal fun contributeDevFixes(
    project: Project,
    environment: MinecraftEnvironment,
    resolved: Provider<ResolvedForge>
) {
    val environmentSpec = project.extensions
        .getByType(RemapExtension::class.java)
        .sourceSet(environment.environmentId)
    val fixesSpec = project.objects
        .newInstance(FG2DevFixesStepSpec::class.java)
    fixesSpec.enabled.set(resolved.map {
        it.profile is ForgeProfile.Binpatches ||
                (it.profile as? ForgeProfile.Userdev)?.config?.notchObf == true
    }.orElse(false))
    environmentSpec.contributeStep(
        RemapSurface.JAR, fixesSpec, StepScope.ENVIRONMENT_ARTIFACT
    )
}

/**
 * Stamp `Automatic-Module-Name: net.minecraftforge.forge` on the dev
 * jar — the merged artifact is named `forge-<v>-merged`, so
 * SecureJarHandler would derive `forge.merged` for the forge modfile
 * the launcher looks up (`updateModuleReads` requires the
 * `net.minecraftforge.forge` module to exist and carry
 * `net.minecraftforge.client.loading.*`). The `minecraft` module is
 * named by client-extra's manifest through `getMinecraftOnly`'s
 * union (first-member manifest wins after UnionFileSystem's
 * reversal). For `userdev` profiles the universal jar also donates
 * its version-bearing `Name:` sections
 * (`net/minecraftforge/versions/forge/`, `…/mcp/`) — FML's
 * `JarVersionLookupHandler` resolves `Package.getSpecificationVersion`
 * from them and `ForgeVersion.<clinit>` throws without it. Matches
 * the attribute on the shipping universal jar. Appended
 * post-pipeline so the remap's rewritten manifest can't drop it.
 */
internal fun contributeMinecraftModuleName(
    project: Project,
    environment: MinecraftEnvironment,
    resolved: Provider<ResolvedForge>
) {
    val environmentSpec = project.extensions
        .getByType(RemapExtension::class.java)
        .sourceSet(environment.environmentId)
    val moduleSpec = project.objects
        .newInstance(JarProcessorsStepSpec::class.java)
    // `resolved` is absent on non-forge envs — the spec is contributed
    // unconditionally (ENVIRONMENT_ARTIFACT scope only excludes it from
    // dependency pipelines, not the env's own transforms), so an
    // unguarded map leaves processorSpecs set-but-absent and transform
    // param isolation fails. Same guard as ForgeAccessWiring's specs.
    moduleSpec.modJars.from(
        resolved.map { listOf(it.jar.toFile()) }.orElse(emptyList())
    )
    moduleSpec.processorSpecs.set(resolved.map { r ->
        val donor = (r.profile as? ForgeProfile.Userdev)
            ?.config?.universal
            ?.let { coord ->
                runCatching {
                    project.configurations.detachedConfiguration(
                        project.dependencies.create(coord)
                    ).apply { isTransitive = false }.files.single().absolutePath
                }.getOrNull()
            }
        listOfNotNull("automatic-module-name", "net.minecraftforge.forge", donor)
            .joinToString(" ")
            .let(::listOf)
    }.orElse(emptyList()))
    environmentSpec.contributeStep(
        RemapSurface.JAR, moduleSpec, StepScope.ENVIRONMENT_ARTIFACT
    )
}

/**
 * Produce the srg-named clean jar `forge-binpatch` verifies against —
 * the mcpconfig `rename` output (`merge` for `notchObf` joined
 * environments), per 1.x `FG3MinecraftTransformer.merge` and FG6
 * `MinecraftUserRepo` (`net.minecraft:joined:<ver>:srg`). Runs the
 * runner into the global unimined cache keyed by the mcpconfig
 * step results re-execute per call — the enclosing artifact
 * transform caches the consuming output.
 */
