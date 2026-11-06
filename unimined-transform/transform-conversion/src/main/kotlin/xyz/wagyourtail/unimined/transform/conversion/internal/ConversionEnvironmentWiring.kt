package xyz.wagyourtail.unimined.transform.conversion.internal

import org.gradle.api.GradleException
import org.gradle.api.Project
import xyz.wagyourtail.unimined.api.spec.Side
import xyz.wagyourtail.unimined.api.util.UniminedCache
import xyz.wagyourtail.unimined.minecraft.MinecraftEnvironment
import xyz.wagyourtail.unimined.minecraft.download.Downloader
import xyz.wagyourtail.unimined.provider.conversion.ConversionProvider
import xyz.wagyourtail.unimined.remap.RemapExtension
import xyz.wagyourtail.unimined.remap.spi.RemapSurface
import xyz.wagyourtail.unimined.remap.spi.StepScope
import xyz.wagyourtail.unimined.transform.BundledLibraryStripStepSpec
import xyz.wagyourtail.unimined.transform.loader.LoaderRegistry
import xyz.wagyourtail.unimined.transform.loader.LoaderRepo
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Duration

/**
 * Env wiring for conversion-family products. The dist jar is a
 * complete MC jar, so the transform is mostly *sourcing + identity*:
 * contribute the dist artifact as the env's declared source, set the
 * identity namespace path, and apply the descriptor's capability
 * library substitutions (patched lwjgl for lwjgl2-era dists).
 * Client+server merge rides the standard merge machinery — the dists
 * ship sided jars, and the merged env's combined artifact is the
 * merge of both dist sides.
 */
object ConversionEnvironmentWiring {

    fun wire(project: Project, environment: MinecraftEnvironment) {
        val spec = environment.loaderSpec.orNull ?: return
        val descriptor = LoaderRegistry().resolve(spec.name)
            ?.takeIf { it.family == "conversion" } ?: return

        // lwjgl2-era dists ride the patched legacyfabric GAV
        descriptor.capabilities["replaceLwjgl"]?.let { target ->
            val match = descriptor.capabilities["replaceLwjglMatch"] ?: "org.lwjgl"
            environment.librarySubstitutions += match to target
        }
        // extra per-product `libsub.<key>` = "match=version" rules
        // (reindev's jinput/asm-all bumps)
        descriptor.capabilities.filterKeys { it.startsWith("libsub.") }
            .forEach { (_, rule) ->
                val idx = rule.lastIndexOf('=')
                if (idx > 0) {
                    environment.librarySubstitutions +=
                        rule.substring(0, idx) to rule.substring(idx + 1)
                }
            }

        // loaders consuming a mod folder (FoxLoader's `mods/`) —
        // prepareRun copies the run's mod jars there. Duck-typed via
        // `property()` — transform modules don't depend on runs.
        descriptor.capabilities["modsDir"]?.let { dir ->
            project.extensions.getByType(
                xyz.wagyourtail.unimined.transform.TransformExtension::class.java
            ).sourceSet(environment.environmentId)
                .prepareTaskMutators += org.gradle.api.Action { task ->
                    @Suppress("UNCHECKED_CAST")
                    (task.property("stageInto") as?
                            org.gradle.api.provider.Property<String>)?.set(dir)
                }
        }

        // loaders that are fabric-loader forks (bta-babric's
        // `net.fabricmc:fabric-loader:*-bta.N`) launch via knot — the
        // version doc's `net.minecraft.launchwrapper.Launch`/`VanillaTweaker`
        // default is wrong for them
        if (descriptor.capabilities["launcher"] == "knot") {
            project.extensions.getByType(
                xyz.wagyourtail.unimined.transform.TransformExtension::class.java
            ).sourceSet(environment.environmentId)
                .runMutators += org.gradle.api.Action<
                    xyz.wagyourtail.unimined.api.spec.RunConfigSpec> { config ->
                    config.mainClass = if (config.name.contains(
                            "server", ignoreCase = true)) {
                        "net.fabricmc.loader.impl.launch.knot.KnotServer"
                    } else {
                        "net.fabricmc.loader.impl.launch.knot.KnotClient"
                    }
                }
        }

        // foxloader launches through its own launcher mains — the
        // invoker patches the MC jar's entry points to call into
        // FoxLauncher (see FoxLoaderPatchProcessor); without it the
        // env would start the vanilla main and never load mods.
        // NO `-Dfoxloader.dev-mode`: that flag selects the 1.x
        // source-dev path (font.txt patched-file lookup + inject-mod
        // classpath hacks); the 2.x flow just needs the patched jar
        // on the classpath and PreLoader copies it into
        // .foxloader/internal/patched/ itself.
        if (descriptor.id == "foxloader") {
            project.extensions.getByType(
                xyz.wagyourtail.unimined.transform.TransformExtension::class.java
            ).sourceSet(environment.environmentId).let { envSpec ->
                envSpec.jarProcessors += FoxLoaderPatchProcessor()
                envSpec.runMutators += org.gradle.api.Action<
                        xyz.wagyourtail.unimined.api.spec.RunConfigSpec> { config ->
                    config.mainClass = if (config.name.contains(
                            "server", ignoreCase = true)) {
                        "com.fox2code.foxloader.launcher.ServerMain"
                    } else {
                        "com.fox2code.foxloader.launcher.ClientMain"
                    }
                }
            }
        }

        // BTA probes `Class.forName("a")` for the obfuscated b1.7.3 base
        // it was built over — the dist is deobf so it's vestigial, but
        // it shows "Beta 1.7.3 is Missing!" when it fails. Inject the
        // stub; self-scopes on the ScreenMissingMinecraft marker.
        project.extensions.getByType(
            xyz.wagyourtail.unimined.transform.TransformExtension::class.java
        ).sourceSet(environment.environmentId)
            .jarProcessors += BtaBetaCheckProcessor()

        // dist-source seam rides provider-conversion: the env's loader
        // claim resolves the dist jar as the MC artifact directly.

        contributeLoaderDependency(project, environment, spec, descriptor)
        contributeBundledLibraryStrip(project, environment)
    }

    /** Loader artifacts onto the env classpaths — conversion envs are
     *  identity-namespace (prod == official == dev), so loader jars
     *  ride raw, never through a remap view. `foxloader` expands per
     *  1.x `FoxLoaderMinecraftTransformer`: `invoker` always; pre-2.9
     *  (`!canCombine`) adds `common`+`<side>` from `sidedArtifacts`,
     *  post-2.9 the unified `loader` coord alone — and sided vs
     *  merged envs are mutually exclusive per-era. */
    private fun contributeLoaderDependency(
        project: Project,
        environment: MinecraftEnvironment,
        spec: xyz.wagyourtail.unimined.api.spec.LoaderSpec,
        descriptor: xyz.wagyourtail.unimined.transform.loader.LoaderDescriptor
    ) {
        // reads hoisted out of withDependencies — provider gets inside
        // a resolution hook re-enter resolution ("Recursive update")
        val mc = environment.version.get()
        val joined = environment.side.orNull == Side.MERGED
        // deps FoxLoader self-manages at runtime — DependencyHelper
        // fetches each into <gameDir>/libraries/ when its classCheck
        // isn't already loadable. They must stay OFF the -cp: a
        // classpath copy plus the libraries/ copy puts the same
        // package on two classloaders and splits runtime packages
        // (IllegalAccessError on package-private members). Everything
        // NOT listed here (asm 9.11 family, annotations, jansi, jna,
        // lwjgl, vineflower...) is expected on -cp — FoxLoader never
        // fetches those.
        val foxLoaderSelfManaged = listOf(
            "com.unascribed" to "ears-api",
            "com.unascribed" to "ears-common",
            "com.google.code.gson" to "gson",
            "it.unimi.dsi" to "fastutil-core",
            "org.slf4j" to "slf4j-api",
            "net.fabricmc" to "sponge-mixin",
            "io.github.llamalad7" to "mixinextras-common",
            "com.github.bawnorton.mixinsquared" to "mixinsquared-common",
            "com.fox2code" to "FoxFlexVer",
            "com.fox2code.FoxEvents" to "core",
            "com.fox2code" to "ReBuild",
            "com.google.guava" to "guava",
            "xyz.wagyourtail.jvmdowngrader" to "jvmdowngrader",
            "xyz.wagyourtail.jvmdowngrader" to "jvmdowngrader-java-api",
            "maven.modrinth" to "spark",
        )
        // foxloader's dep set — shared by the env `loader` config and
        // the patch-tooling config below.
        fun addFoxLoaderDeps(
            deps: org.gradle.api.artifacts.DependencySet,
            transitive: Boolean,
            excludeSelfManaged: Boolean = false
        ) {
            // version-pair check rides the CDN map — fetched here, at
            // resolution time, never during config; blank version
            // picks the newest compatible pair
            val version = FoxLoaderVersionMap.resolve(
                fetchFoxLoaderPairs(project), mc, spec.version
            )
            // era split is on the DIST (reindev) version — reindev 2.9
            // ships the merged `loader` artifact; the resolved
            // foxloader version only selects the version inside the
            // pair, not the artifact shape
            val joinedCapable = canCombine(mc)
            if (joined && !joinedCapable)
                throw GradleException(
                    "foxloader $version predates 2.9 and " +
                    "cannot run in a merged (joined) env"
                )
            fun dep(notation: String) = project.dependencies.create(notation)
                .also {
                    (it as? org.gradle.api.artifacts.ModuleDependency)?.let { md ->
                        md.isTransitive = transitive
                        if (excludeSelfManaged)
                            foxLoaderSelfManaged.forEach { (g, a) ->
                                md.exclude(mapOf("group" to g, "module" to a))
                            }
                    }
                }
            descriptor.capabilities["invoker"]?.let { deps.add(dep(it)) }
            if (joinedCapable) {
                deps.add(dep(descriptor.loaderCoords(mc, version)))
            } else {
                // pre-2.9 has no `loader` artifact — the dep set is
                // common + <side> instead
                val side = environment.side.orNull!!.name.lowercase()
                descriptor.capabilities["sidedArtifacts"]
                    ?.split(',')?.forEach { coord ->
                        deps.add(dep(coord.replace("<side>", side) + ":" + version))
                    }
            }
        }
        val loader = project.configurations.create(
            "conversionLoader${environment.environmentId.replaceFirstChar(Char::uppercase)}"
        ) { conf ->
            conf.isCanBeConsumed = false
            conf.isCanBeResolved = true
            // knot-launch loaders (bta-babric's fabric-loader fork)
            // need their declared deps — fabric-loader doesn't bundle
            // `org.objectweb.asm`, it resolves it transitively.
            // foxloader needs its pom transitives too (asm 9.11 for
            // the runtime patcher) minus the self-managed set the
            // dep() excludes strip.
            conf.isTransitive = descriptor.capabilities["launcher"] == "knot" ||
                    descriptor.id == "foxloader"
            conf.withDependencies { deps ->
                var addPrimary = true
                if (descriptor.id == "foxloader") {
                    addPrimary = false
                    addFoxLoaderDeps(deps, transitive = true,
                        excludeSelfManaged = true)
                }
                if (addPrimary) {
                    if (spec.version.isBlank())
                        throw GradleException(
                            "loader '${spec.name}' requires an explicit " +
                            "version — only foxloader resolves one from " +
                            "its version map"
                        )
                    deps.add(
                        project.dependencies.create(
                            descriptor.loaderCoords(mc, spec.version)
                        )
                    )
                }
                // knot-launch loaders (fabric-loader forks) publish
                // their runtime deps in a `<ver>.json` metadata doc on
                // the publishing repo — the bta pom is a stub (no deps)
                // and the version doc's legacy `asm-all`/`asm-debug-all`
                // can't read v52 anyway, so ride the metadata doc
                if (descriptor.capabilities["launcher"] == "knot") {
                    fetchLoaderLibraries(project, environment, descriptor, spec)
                        .forEach { deps.add(project.dependencies.create(it)) }
                }
            }
        }
        // foxloader's patch tooling — the invoker subprocess needs
        // the loader jar PLUS its transitives (PatchBridge →
        // GamePatches → asm), which the env classpath deliberately
        // doesn't carry. Its own configuration so the transitive
        // resolution never reaches the runtime classpath.
        val tooling = if (descriptor.id == "foxloader") {
            project.configurations.create(
                "conversionLoader${environment.environmentId.replaceFirstChar(Char::uppercase)}Tooling"
            ) { conf ->
                conf.isCanBeConsumed = false
                conf.isCanBeResolved = true
                conf.isTransitive = true
                conf.withDependencies { deps ->
                    addFoxLoaderDeps(deps, transitive = true)
                }
            }
        } else null
        // the invoker classpath FoxLoaderPatchProcessor execs —
        // surfaced via `SpecContext.envArtifacts` lazily; the
        // configuration resolves at spec-build time, never here.
        if (descriptor.id == "foxloader") {
            val envSpec = project.extensions.getByType(
                xyz.wagyourtail.unimined.transform.TransformExtension::class.java
            ).sourceSet(environment.environmentId)
            envSpec.envArtifactBindings["foxloader:invoker"] = {
                tooling!!.files.map { it.toPath() }
            }
            // the other dist — foxloader 2.x's unified patcher needs
            // the full client+server class set; the processor builds
            // a superset input and strips the other side back out
            // (reindev sets `fetchBothSides` so the key exists)
            envSpec.envArtifactBindings["foxloader:otherSide"] = {
                val artifacts = project.extensions.getByType(
                    xyz.wagyourtail.unimined.minecraft.MinecraftExtension::class.java
                ).artifacts(environment.environmentId).get()
                val otherKey = if (environment.side.orNull == Side.CLIENT) {
                    xyz.wagyourtail.unimined.minecraft.spi.ArtifactKey.SERVER
                } else {
                    xyz.wagyourtail.unimined.minecraft.spi.ArtifactKey.CLIENT
                }
                artifacts.artifacts[otherKey]
                    ?.let { listOf(it.get().asFile.toPath()) }
                    .orEmpty()
            }
        }
        val sourceSet = project.extensions
            .getByType(org.gradle.api.plugins.JavaPluginExtension::class.java)
            .sourceSets.findByName(environment.environmentId) ?: return
        listOf(
            sourceSet.compileClasspathConfigurationName,
            sourceSet.runtimeClasspathConfigurationName,
        ).forEach { name ->
            project.configurations.named(name) { conf ->
                conf.extendsFrom(loader)
            }
        }
    }

    /** FoxLoader's joined-artifact cutoff — 1.x `canCombine`: the
     *  merged `loader` artifact exists from 2.9 onward. */
    private fun canCombine(version: String): Boolean {
        val parts = version.split('.', '-', '_')
            .mapNotNull { it.toIntOrNull() }
        return (parts.getOrElse(0) { 0 } > 2) ||
               (parts.getOrElse(0) { 0 } == 2 &&
                parts.getOrElse(1) { 0 } >= 9)
    }

    /**
     * Fetch the FoxLoader↔ReIndev version map once through the cached
     * [Downloader] (TTL + offline fallback, same as manifests).
     * Called from `withDependencies` — resolution time, never during
     * configuration. Honors `--offline`, `--refresh-dependencies`,
     * `unimined.forceReload`, `unimined.refreshDownloads`, and
     * `unimined.metadataCacheTtl`, mirroring ProviderCacheSettings.
     */
    private fun fetchFoxLoaderPairs(project: Project): Map<String, String> {
        val downloader = Downloader(
            cacheDir = UniminedCache.global(project)
                .resolve("transform-conversion"),
            offline = project.gradle.startParameter.isOffline,
            refreshCache = project.gradle.startParameter.isRefreshDependencies ||
                    flag(project, "unimined.forceReload") ||
                    flag(project, "unimined.refreshDownloads"),
            metadataTtl = project.providers
                .gradleProperty("unimined.metadataCacheTtl").orNull
                ?.let(Duration::parse) ?: Duration.ofHours(24),
        )
        return FoxLoaderVersionMap.parse(
            downloader
                .fetchMetadata(FoxLoaderVersionMap.MAP_URL, "foxloader-version-map.json")
                .toFile().readText()
        )
    }

    /** knot-launch loaders (fabric-loader forks) publish their runtime
     *  dep set as `<artifact>-<version>.json` next to the jar on the
     *  publishing repo — FabricMeta shape: `libraries.{common,client,
     *  server}` entries carrying `name` coords. The bta pom is a stub
     *  so the doc is the authoritative set; the first repo serving it
     *  wins, a repo that lacks the doc just skips it. */
    private fun fetchLoaderLibraries(
        project: Project,
        environment: MinecraftEnvironment,
        descriptor: xyz.wagyourtail.unimined.transform.loader.LoaderDescriptor,
        spec: xyz.wagyourtail.unimined.api.spec.LoaderSpec,
    ): List<String> {
        val parts = descriptor.loaderCoords(
            environment.version.get(), spec.version
        ).split(':')
        if (parts.size < 3 || spec.version.isBlank()) return emptyList()
        val path = "${parts[0].replace('.', '/')}/${parts[1]}/${parts[2]}" +
                "/${parts[1]}-${parts[2]}.json"
        val downloader = Downloader(
            cacheDir = UniminedCache.global(project)
                .resolve("transform-conversion"),
            offline = project.gradle.startParameter.isOffline,
            refreshCache = project.gradle.startParameter.isRefreshDependencies ||
                    flag(project, "unimined.forceReload") ||
                    flag(project, "unimined.refreshDownloads"),
            metadataTtl = project.providers
                .gradleProperty("unimined.metadataCacheTtl").orNull
                ?.let(Duration::parse) ?: Duration.ofHours(24),
        )
        val file = descriptor.repos.filterIsInstance<LoaderRepo.Maven>()
            .firstNotNullOfOrNull { repo ->
                runCatching {
                    downloader.fetchMetadata(
                        "${repo.url.trimEnd('/')}/$path",
                        "loader-${parts[1]}-${parts[2]}.json",
                    )
                }.getOrNull()
            } ?: return emptyList()
        val libs = Json.parseToJsonElement(file.toFile().readText())
            .jsonObject["libraries"]?.jsonObject ?: return emptyList()
        val sides = listOf("common") + when (environment.side.orNull) {
            Side.SERVER -> listOf("server")
            Side.CLIENT -> listOf("client")
            else -> listOf("client", "server")
        }
        return sides.flatMap { key ->
            (libs[key] as? JsonArray)?.mapNotNull {
                it.jsonObject["name"]?.jsonPrimitive?.content
            } ?: emptyList()
        }.distinct()
    }

    private fun flag(project: Project, name: String): Boolean =
        project.providers.gradleProperty(name).orNull?.toBoolean() == true

    /**
     * Products declaring `extraLibraries` shade copies of those deps
     * into the dist jar — the declared coordinates ride the classpath
     * and the bundled duplicates strip out of the env artifact. Runs
     * pre-pipeline as artifact prep, scoped to the env artifact (the
     * membership set is the env's declared libraries — stripping a mod
     * jar against it would delete the mod's classes).
     *
     * MERGED envs get no step: the producer strips both sides
     * *pre-merge* inside `ConversionArtifactProducer` — the pipeline
     * only ever sees the merged output, and stripping there would
     * leave a redundant fat merged.jar in the artifact cache.
     */
    private fun contributeBundledLibraryStrip(
        project: Project,
        environment: MinecraftEnvironment
    ) {
        if (environment.side.orNull == Side.MERGED) return
        if (!ConversionProvider.declaresExtraLibraries(environment.product.get())) return
        val strip = project.objects
            .newInstance(BundledLibraryStripStepSpec::class.java)
        strip.libraryJars.from(
            project.configurations.getByName(
                environment.minecraftDeclaredLibrariesConfigurationName
            )
        )
        project.extensions.getByType(RemapExtension::class.java)
            .sourceSet(environment.environmentId)
            .contributePreStep(
                RemapSurface.JAR, strip, StepScope.ENVIRONMENT_ARTIFACT
            )
    }
}
