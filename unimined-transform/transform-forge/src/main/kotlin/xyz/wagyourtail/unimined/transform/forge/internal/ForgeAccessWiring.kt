package xyz.wagyourtail.unimined.transform.forge.internal

import xyz.wagyourtail.commonskt.utils.capitalized
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
import xyz.wagyourtail.unimined.transform.forge.internal.StringClassNamesStepSpec
import xyz.wagyourtail.unimined.transform.forge.internal.installer.ResolvedForge
import xyz.wagyourtail.unimined.transform.forge.internal.userdev.UserdevEnrichers
import xyz.wagyourtail.unimined.transform.forge.internal.userdev.UserdevExtract
import xyz.wagyourtail.unimined.transform.forge.internal.userdev.UserdevRunConfigs
import xyz.wagyourtail.unimined.api.util.UniminedCache

/**
 * Access-transform wiring — the largest contribution. Reads the
 * userdev profile's AT list (`userdevAts`), folds them with mod-declared
 * ATs/AWs/class-tweakers into the environment's [AccessModel], and
 * registers `apply-class-tweaker` + `remap-access` steps on the right
 * surfaces so the dev jar sees widened members and the modsource
 * analysis view sees the pre-AT hierarchy where required.
 */
internal fun userdevAts(
    project: Project,
    resolved: Provider<ResolvedForge>,
    cacheDir: Path
): Provider<List<Path>> = project.provider {
    val r = resolved.orNull ?: return@provider emptyList()
    val profile = r.profile as? ForgeProfile.Userdev ?: return@provider emptyList()
    profile.config.ats.flatMap {
        UserdevExtract.extractPrefix(
            r.jar, it, cacheDir.resolve(UniminedCache.artifactKey(r.jar))
        )
    }
}

/**
 * `Userdev` profile: the installer jar's `libraries` become dev
 * classpath deps — `legacydev` skipped (it's a mcpconfig function
 * dep, not a runtime library) and `gson:2.8.0` pinned to `2.8.9`
 * (1.x parity).
 */
internal fun contributeAccessSteps(
    project: Project,
    environment: MinecraftEnvironment,
    resolved: Provider<ResolvedForge>
) {
    val objects: ObjectFactory = project.objects
    val loaderSpec: Property<LoaderSpec> = environment.loaderSpec
    val remap = project.extensions.getByType(RemapExtension::class.java)
    val environmentSpec = remap.sourceSet(environment.environmentId)

    val modJars = project.configurations.create(
        "forgeModAnalysis${environment.environmentId.capitalized()}"
    ) { conf ->
        conf.isCanBeConsumed = false
        conf.isCanBeResolved = true
        conf.extendsFrom(
            project.configurations.getByName(environment.modImplementationConfigurationName)
        )
        conf.attributes.attribute(
            ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE,
            ArtifactTypeDefinition.JAR_TYPE
        )
    }
    val modJarFiles = project.files(modJars)
    // extractions are keyed by source jar (name+size+mtime) —
    // global cache, no env/coord collisions
    val forgeCache = UniminedCache.global(project).resolve("forge")
    val udAts = userdevAts(project, resolved, forgeCache.resolve("ats"))
    // FG1/FG2-era: `forge_at.cfg`/`fml_at.cfg` entries at the
    // root of the universal jar (1.x `accessTransformerPaths`)
    val legacyAts = project.provider {
        val r = resolved.orNull ?: return@provider emptyList<Path>()
        when (val profile = r.profile) {
            is ForgeProfile.Binpatches ->
                // dev package: `merged_at.cfg` + universal entries;
                // universal-only: `forge_at.cfg`/`fml_at.cfg`
                UserdevExtract.extractEntries(
                    profile.devPackage?.jar ?: r.jar,
                    if (profile.devPackage != null)
                        listOf("merged_at.cfg", "forge_at.cfg", "fml_at.cfg")
                    else
                        listOf("forge_at.cfg", "fml_at.cfg"),
                    forgeCache.resolve(
                        "legacy-ats/" +
                                UniminedCache.artifactKey(r.jar)
                    )
                )

            is ForgeProfile.ZipPatches ->
                UserdevExtract.extractEntries(
                    r.jar,
                    listOf("forge_at.cfg", "fml_at.cfg"),
                    forgeCache.resolve(
                        "legacy-ats/" +
                                UniminedCache.artifactKey(r.jar)
                    )
                )

            else -> emptyList()
        }
    }
    // every dep-declared AT, unconditionally — ATs apply to the
    // shared artifact by FML semantics
    val extractedAts = project.provider {
        if (resolved.orNull == null) {
            emptyList()
        } else {
            modJarFiles.files.flatMap {
                ForgeModAnalysis.extractClassTweakers(
                    it.toPath(),
                    forgeCache.resolve(
                        "ct/" + UniminedCache.artifactKey(it.toPath())
                    )
                )
            }
        }
    }

    fun enricherDir(jar: Path) = forgeCache.resolve(
        "enrichers/" + UniminedCache.artifactKey(jar)
    )
    // FG6-era `data` enrichers: `access` (raw AT file) +
    // interfaces/enum converted to generated classTweaker files in
    // the userdev obf namespace (the step rebases from there)
    val enricherCts = project.provider {
        val r = resolved.orNull ?: return@provider emptyList<Path>()
        val profile = r.profile as? ForgeProfile.Userdev
            ?: return@provider emptyList()
        val side = when (environment.side.get()) {
            Side.CLIENT -> "client"
            Side.SERVER -> "server"
            Side.MERGED -> "joined"
        }
        val paths = listOf("access", "interfaces", "enum")
            .mapNotNull { key ->
                UserdevEnrichers.dataPath(profile.config, key, side)
                    ?.let { key to it }
            }.toMap()
        if (paths.isEmpty()) return@provider emptyList()
        val extracted = UserdevEnrichers.extract(r.jar, paths, enricherDir(r.jar))
        UserdevEnrichers.classTweakerFiles(
            extracted, profile.config.obfNamespace, enricherDir(r.jar)
        )
    }

    // Headerless `*_at.cfg` files declare a literal `source`
    // namespace — bind it per origin: extracted ATs (userdev `ats`,
    // universal-jar cfgs, dep-mod ATs) name the loader's obf
    // namespace (srg/searge names — FML applies them against the
    // obf-named runtime); user-authored ATs are in dev names
    // (1.x `at2aw` defaulted to devNamespace).
    val extractedObfNs = resolved.map { r ->
        (r.profile as? ForgeProfile.Userdev)?.config?.obfNamespace
            ?: "searge"
    }
    val extractedSpec = objects.newInstance(ApplyClassTweakerStepSpec::class.java)
    extractedSpec.classTweakerFiles.from(extractedAts)
    extractedSpec.classTweakerFiles.from(udAts)
    extractedSpec.classTweakerFiles.from(legacyAts)
    extractedSpec.classTweakerFiles.from(enricherCts)
    extractedSpec.headerlessNamespace.set(extractedObfNs)

    // explicit `devNamespace` is often unset — the effective value
    // comes from the mapping tree's last namespace
    val effectiveDevNs = project.extensions.getByType(
        MinecraftExtension::class.java
    ).devNamespace(environment.environmentId)

    val userSpec = objects.newInstance(ApplyClassTweakerStepSpec::class.java)
    userSpec.classTweakerFiles.from(environment.accessTransformers)
    userSpec.headerlessNamespace.set(effectiveDevNs)

    // the remap-analysis overlay: declared ATs plus every extracted
    // dep AT, as AccessModel sections — same namespace binding
    environmentSpec.contributeAccessModel(project.provider {
        val devNs = mapOf("source" to effectiveDevNs.get())
        val obfNs = mapOf("source" to extractedObfNs.get())
        AccessModel(
            buildList {
                environment.accessTransformers.files
                    .filter { it.isFile }
                    .mapTo(this) {
                        ClassTweaker.parse(it.name, it.readText(), devNs)
                            .toAccessModelSection(it.name)
                    }
                (extractedAts.get() + udAts.get()).mapTo(this) {
                    ClassTweaker.parse(
                        it.fileName.toString(), it.toFile().readText(), obfNs
                    )
                        .toAccessModelSection(it.fileName.toString())
                }
                enricherCts.get().filter {
                    it.fileName.toString().endsWith("access") ||
                            "access-" in it.fileName.toString()
                }.mapTo(this) {
                    ClassTweaker.parse(it.fileName.toString(), it.toFile().readText())
                        .toAccessModelSection(it.fileName.toString())
                }
            }
        )
    })

    environmentSpec.contributeStep(RemapSurface.JAR, extractedSpec)
    environmentSpec.contributeStep(RemapSurface.JAR, userSpec)
    environmentSpec.contributeStep(RemapSurface.JAR, AccessRemapStepSpec())

    // string-LDC class names in forge's reflection classes —
    // scoped to ObjectHolderRegistry across its three era
    // packages (1.x StringClassNameRemapExtension parity); runs
    // on both Userdev and Binpatches profiles so dev-namespace
    // names resolve under yarn/mcp/mojmap
    environmentSpec.contributeStep(
        RemapSurface.JAR,
        objects.newInstance(StringClassNamesStepSpec::class.java).apply {
            enabled.set(resolved.map {
                it.profile is ForgeProfile.Userdev ||
                        it.profile is ForgeProfile.Binpatches
            })
            classes.set(
                listOf(
                    "net/minecraftforge/registries/ObjectHolderRegistry",
                    "net/minecraftforge/fml/common/registry/ObjectHolderRegistry",
                    "net/neoforged/neoforge/registries/ObjectHolderRegistry"
                )
            )
        }
    )

    // the same enrichers on the SOURCES surface via `jst` —
    // ForgeGradle 6 parity (1.x never did this): generated sources
    // show ATs and injected interfaces as source
    val jstSpec = objects.newInstance(JstStepSpec::class.java)
    jstSpec.toolClasspath.from(project.provider {
        val r = resolved.orNull ?: return@provider emptyList<Any>()
        if (r.profile !is ForgeProfile.Userdev) return@provider emptyList<Any>()
        val conf = project.configurations.detachedConfiguration(
            // transitive — picocli/problems-api/IntelliJ-platform are
            // real runtime deps of jst-cli; the `-jb` artifacts resolve
            // from the content-filtered JetBrains repos in GradlePlugin
            project.dependencies.create("net.neoforged.jst:jst-cli:2.0.11")
        )
        conf.files.toList()
    })
    jstSpec.enable.set(project.provider {
        val r = resolved.orNull ?: return@provider emptyList()
        val profile = r.profile as? ForgeProfile.Userdev
            ?: return@provider emptyList()
        buildList {
            if (UserdevEnrichers.dataPath(profile.config, "access", "client") != null)
                add("accesstransformers")
            if (UserdevEnrichers.dataPath(profile.config, "interfaces", "client") != null)
                add("interface-injection")
        }
    })
    jstSpec.extraArgs.set(project.provider {
        val r = resolved.orNull ?: return@provider emptyList()
        val profile = r.profile as? ForgeProfile.Userdev
            ?: return@provider emptyList()
        val side = when (environment.side.get()) {
            Side.CLIENT -> "client"
            Side.SERVER -> "server"
            Side.MERGED -> "joined"
        }
        buildList {
            UserdevEnrichers.dataPath(profile.config, "access", side)?.let {
                add(
                    "--access-transformer=" +
                            enricherDir(r.jar).resolve("access-" + it.substringAfterLast('/'))
                )
            }
            UserdevEnrichers.dataPath(profile.config, "interfaces", side)?.let {
                add(
                    "--interface-injection-data=" +
                            enricherDir(r.jar).resolve("interfaces-" + it.substringAfterLast('/'))
                )
            }
        }
    })
    environmentSpec.contributeStep(RemapSurface.SOURCES, jstSpec)

    // Userdev `inject`: prefix-stripped installer entries merged
    // into the dev jar via the jar-processors channel
    val jarProcessorsSpec = objects.newInstance(JarProcessorsStepSpec::class.java)
    jarProcessorsSpec.modJars.from(resolved.map { listOf(it.jar.toFile()) })
    // `set` — `add` would discard the implicit-empty base and a
    // null-yielding provider leaves the property set-but-absent;
    // no `inject`/`universal` on non-Userdev profiles → empty list
    jarProcessorsSpec.processorSpecs.set(resolved.map { r ->
        val profile = r.profile as? ForgeProfile.Userdev
            ?: return@map emptyList()
        buildList {
            profile.config.inject?.let { prefix ->
                val processor = UserdevInjectProcessor()
                add(
                    "userdev-inject " + processor.serializeSpec(
                        UserdevInjectProcessor.Spec(r.jar, prefix)
                    )
                )
            }
        }
    })
    environmentSpec.contributePreStep(
        RemapSurface.JAR, jarProcessorsSpec, StepScope.ENVIRONMENT_ARTIFACT
    )

    // userdev shades the universal jar into the dev artifact —
    // FML's `findJarOnClasspath(forge-<mc>-<ver>)` hands the single
    // matched jar to the `minecraft` mod content, so forge classes
    // + META-INF/mods.toml must live inside it (FG6's merged
    // `forge-<v>_mapped_*` artifact shape). Pre-remap for every
    // era: the universal's loader classes reference Minecraft at
    // the `obfNamespace` boundary (obf for notchObf, srg for
    // modern userdev — same as the binpatch step's output), so
    // merging before the core rename lets that rename carry both
    // Minecraft and forge classes' MC references into devNamespace.
    val shadeSpec = objects.newInstance(JarProcessorsStepSpec::class.java)
    shadeSpec.processorSpecs.set(resolved.map { r ->
        val profile = r.profile as? ForgeProfile.Userdev
            ?: return@map emptyList()
        profile.config.universal?.let { coord ->
            runCatching {
                project.configurations.detachedConfiguration(
                    project.dependencies.create(coord)
                ).apply { isTransitive = false }.files.single()
            }.getOrNull()?.toPath()?.let { universalJar ->
                val processor = UserdevUniversalShadeProcessor()
                listOf(
                    "userdev-universal-shade " +
                            processor.serializeSpec(
                                UserdevUniversalShadeProcessor.Spec(
                                    universalJar,
                                    // FG's install filters apply —
                                    // they exclude
                                    // `binpatches.pack.lzma`, whose
                                    // runtime ClassPatchManager
                                    // checksums target the pre-patch
                                    // jar and fatal on the
                                    // already-patched dev jar
                                    profile.config.universalFilters
                                )
                            )
                )
            }
        } ?: emptyList()
    })
    environmentSpec.contributePreStep(
        RemapSurface.JAR, shadeSpec, StepScope.ENVIRONMENT_ARTIFACT
    )
}

/**
 * Run-config mutators — maps the profile's run definitions into
 * `RunConfigSpec` adjustments at execution time:
 * - `Userdev`: `runs.<side>` via [UserdevRunConfigs] — `MOD_CLASSES`
 *   `{source_roots}` groups, asset index, srg-mcp path, bootstrap-dev
 *   `<2.1.4` `;` separator quirk.
 * - `Binpatches`: the FG2 GradleStart/launchwrapper shape — jar-content
 *   `FMLTweaker`/`FMLServerTweaker` detection (cpw vs net.minecraftforge
 *   package), `fml.deobfuscatedEnvironment`, `GradleStart.srg.srg-mcp`.
 *
 * Runs whose name isn't a declared side (`runs` key or
 * client/server) are untouched.
 */
