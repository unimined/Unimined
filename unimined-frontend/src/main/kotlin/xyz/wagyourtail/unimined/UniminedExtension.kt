package xyz.wagyourtail.unimined

import groovy.lang.Closure
import groovy.lang.MissingMethodException
import groovy.lang.DelegatesTo
import org.gradle.api.Action
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.provider.Property
import xyz.wagyourtail.unimined.api.Deprecations
import xyz.wagyourtail.unimined.api.util.delegatingTo
import xyz.wagyourtail.unimined.minecraft.MinecraftExtension
import xyz.wagyourtail.unimined.deps.LoaderApi
import xyz.wagyourtail.unimined.mod.ModExtension
import xyz.wagyourtail.unimined.migrate.MigrateExtension
import xyz.wagyourtail.unimined.migrate.task.MigrateMappingsTask
import xyz.wagyourtail.unimined.remap.RemapExtension
import xyz.wagyourtail.unimined.transform.TransformExtension
import javax.inject.Inject

/**
 * The `unimined { }` facade — an aggregator presenting one DSL that fans
 * out to the underlying plugins' extensions. Each underlying plugin
 * remains independently applicable; this only folds them into one
 * interface. Transform-agnostic: transforms declare themselves through
 * the transform builder API on [TransformExtension].
 */
abstract class UniminedExtension @Inject constructor(private val project: Project) {

    /**
     * 1.x `unimined { }` verbs removed or re-shaped in 2.0 — Groovy
     *  scripts hit [methodMissing] and get a migration note instead
     *  of a bare missing-method error. Warnings, not emulation.
     */
    private val removed1xVerbs: Map<String, String> = buildMap {
        // env blocks moved inside unimined.minecraft { sourceSet(name) {} }
        this["reIndev"] = "the ReIndev preset is `reindev { }` in 2.0 — `unimined { reindev { ... } }` or per-env `minecraft { sourceSet(\"x\") { reindev { } } }`"
        this["modsRemapRepo"] = "mod dependencies resolve through unimined's managed repositories automatically — remove this call"
        // repo helpers: maven repos are auto-registered per loader
        val repos = "maven repositories are auto-registered per loader environment in 2.0 — remove this call (custom repos can be added via `repositories {}`)"
        listOf(
            "minecraftForgeMaven", "neoForgedMaven", "fabricMaven",
            "ornitheMaven", "legacyFabricMaven", "quiltMaven",
            "glassLauncherMaven", "wispForestMaven", "sleepingTownMaven",
            "wagYourMaven", "mcphackersIvy", "parchmentMaven",
            "sonatypeStaging", "spongeMaven", "jitpack", "spigot",
            "flintMaven", "cleanroomRepos", "arcseekersMaven",
            "fox2codeMaven", "signalumMaven", "modrinthMaven",
            "curseMaven", "liteloaderMaven"
        ).forEach { this[it] = repos }
    }

    /** Migration bridge for removed 1.x `unimined { }` verbs. */
    fun methodMissing(name: String, args: Any): Any? {
        val argArray = args as Array<*>
        if (name == "minecraft" && argArray.isNotEmpty()) {
            throw GradleException(
                "unimined.minecraft(<sourceSet>) { } moved in Unimined 2.0 — " +
                "use `unimined.minecraft { }` and configure the source-set " +
                "environment inside (`minecraft { sourceSet(<name>) { } }`). " +
                "See Writerside/topics/Migration-1x-to-2.0.md for the 2.0 migration notes."
            )
        }
        removed1xVerbs[name]?.let {
            throw GradleException(
                "unimined.$name(...) was removed in Unimined 2.0 — $it"
            )
        }
        throw MissingMethodException(name, javaClass,
            argArray.filterNotNull().toTypedArray())
    }


    /**
     * Global java-toolchain kill-switch — delegates to
     * [MinecraftExtension.useToolchains]. When false, tool execs,
     * compiles, and run launchers use the build JVM (which must
     * satisfy each environment's java requirement).
     */
    var useToolchains: Boolean
        get() = project.extensions.getByType(MinecraftExtension::class.java)
            .useToolchains.get()
        set(value) = project.extensions.getByType(MinecraftExtension::class.java)
            .useToolchains.set(value)

    /**
     * Default source-set java floor — delegates to
     * [MinecraftEnvironment.javaVersion] on the `main` environment
     * (`unimined { javaVersion = 21 }` to override the version-json /
     * loader-declared requirement).
     */
    val javaVersion: Property<Int>
        get() = project.extensions.getByType(MinecraftExtension::class.java)
            .main.javaVersion

    /** `unimined.minecraft { }` → the `unimined-minecraft` extension. */
    fun minecraft(action: Action<MinecraftExtension>) {
        action.execute(project.extensions.getByType(MinecraftExtension::class.java))
    }

    private fun minecraftExt(): MinecraftExtension =
        project.extensions.getByType(MinecraftExtension::class.java)

    /** `unimined.bta { }` — Better Than Adventure dist preset on `main`
     *  (`provider("conversion")` + `product("minecraft:bta")`). */
    fun bta(action: Action<xyz.wagyourtail.unimined.minecraft.DistSpec>) =
        minecraftExt().bta(action)

    /** Groovy-DSL counterpart to [bta]. */
    fun bta(@DelegatesTo(value = xyz.wagyourtail.unimined.minecraft.DistSpec::class,
        strategy = Closure.DELEGATE_FIRST) action: Closure<*>) =
        minecraftExt().bta(action)

    /** `unimined.reindev { }` — ReIndev dist preset on `main`. */
    fun reindev(action: Action<xyz.wagyourtail.unimined.minecraft.DistSpec>) =
        minecraftExt().reindev(action)

    /** Groovy-DSL counterpart to [reindev]. */
    fun reindev(@DelegatesTo(value = xyz.wagyourtail.unimined.minecraft.DistSpec::class,
        strategy = Closure.DELEGATE_FIRST) action: Closure<*>) =
        minecraftExt().reindev(action)

    /** `unimined.nsss { }` — NSSS dist preset on `main`; declare
     *  `file()`/`url()` inside (no upstream index). */
    fun nsss(action: Action<xyz.wagyourtail.unimined.minecraft.DistSpec>) =
        minecraftExt().nsss(action)

    /** Groovy-DSL counterpart to [nsss]. */
    fun nsss(@DelegatesTo(value = xyz.wagyourtail.unimined.minecraft.DistSpec::class,
        strategy = Closure.DELEGATE_FIRST) action: Closure<*>) =
        minecraftExt().nsss(action)

    /** `unimined.custom("name") { }` — user-supplied dist jar on
     *  `main`; `product` conventions to `custom:<name>`. */
    fun custom(name: String,
               action: Action<xyz.wagyourtail.unimined.minecraft.DistSpec>) =
        minecraftExt().custom(name, action)

    /** Groovy-DSL counterpart to [custom]. */
    fun custom(name: String,
               @DelegatesTo(value = xyz.wagyourtail.unimined.minecraft.DistSpec::class,
                   strategy = Closure.DELEGATE_FIRST) action: Closure<*>) =
        minecraftExt().custom(name, action)

    // region 1.x deprecation bridge — moved DSL (plan §Phase 8)

    /** `unimined.runs { }` → the `unimined-runs` extension — same
     *  object as the top-level `runs { }` (that stays the canonical
     *  DSL; this is the umbrella accessor). */
    fun runs(action: Action<xyz.wagyourtail.unimined.runs.RunsExtension>) {
        action.execute(project.extensions.getByType(
            xyz.wagyourtail.unimined.runs.RunsExtension::class.java))
    }

    /** Groovy DSL overload; delegates the closure to the runs extension. */
    fun runs(
        @DelegatesTo(value = xyz.wagyourtail.unimined.runs.RunsExtension::class,
            strategy = Closure.DELEGATE_FIRST) action: Closure<*>
    ) {
        val extension = project.extensions.getByType(
            xyz.wagyourtail.unimined.runs.RunsExtension::class.java)
        action.delegate = extension
        action.resolveStrategy = Closure.DELEGATE_FIRST
        action.call()
    }

    /** `unimined.mapping { }` → the `unimined-mapping` extension. */
    fun mapping(action: Action<xyz.wagyourtail.unimined.mapping.MappingExtension>) {
        action.execute(project.extensions.getByType(
            xyz.wagyourtail.unimined.mapping.MappingExtension::class.java))
    }

    /** Groovy DSL overload; delegates the closure to the mapping extension. */
    fun mapping(
        @DelegatesTo(value = xyz.wagyourtail.unimined.mapping.MappingExtension::class,
            strategy = Closure.DELEGATE_FIRST) action: Closure<*>
    ) {
        val extension = project.extensions.getByType(
            xyz.wagyourtail.unimined.mapping.MappingExtension::class.java)
        action.delegate = extension
        action.resolveStrategy = Closure.DELEGATE_FIRST
        action.call()
    }

    /** `unimined.source { }` → the `unimined-source` extension. */
    fun source(action: Action<xyz.wagyourtail.unimined.source.SourceExtension>) {
        action.execute(project.extensions.getByType(
            xyz.wagyourtail.unimined.source.SourceExtension::class.java))
    }

    /** Groovy DSL overload; delegates the closure to the source extension. */
    fun source(
        @DelegatesTo(value = xyz.wagyourtail.unimined.source.SourceExtension::class,
            strategy = Closure.DELEGATE_FIRST) action: Closure<*>
    ) {
        val extension = project.extensions.getByType(
            xyz.wagyourtail.unimined.source.SourceExtension::class.java)
        action.delegate = extension
        action.resolveStrategy = Closure.DELEGATE_FIRST
        action.call()
    }

    @Deprecated("moved to top-level in 2.0", level = DeprecationLevel.ERROR)
    fun mods(action: Action<Any>): Nothing =
        Deprecations.movedDsl("unimined { mods { } }", "mod { } (top level)")

    @Deprecated("per-sourceSet envs became named envs in 2.0",
        level = DeprecationLevel.ERROR)
    fun minecraft(sourceSet: org.gradle.api.tasks.SourceSet,
                  action: Action<Any>): Nothing =
        Deprecations.movedDsl(
            "unimined { minecraft(sourceSets.<ss>) { } }",
            "per-environment config (multi-env layout is a follow-up)"
        )

    // endregion

    /** Groovy DSL overload; delegates the closure to the Minecraft extension. */
    fun minecraft(
        @DelegatesTo(value = MinecraftExtension::class, strategy = Closure.DELEGATE_FIRST)
        action: Closure<*>
    ) {
        val extension = project.extensions.getByType(MinecraftExtension::class.java)
        action.delegate = extension
        action.resolveStrategy = Closure.DELEGATE_FIRST
        action.call()
    }

    /**
     * `unimined.migrateMappings("main") { target { yarn(...) } }` —
     * registers the `migrateMappings<SS>` task migrating the source
     * set's sources onto a different mappings release — delegates to
     * the `unimined-migrate` plugin's `migrate` extension (see
     * `MigrateMappingsTask` / plan.md §6).
     */
    /** Groovy DSL overload — `unimined.migrateMappings("main") { target { ... } }`. */
    fun migrateMappings(
        environmentId: String,
        @DelegatesTo(value = MigrateMappingsTask::class, strategy = Closure.DELEGATE_FIRST)
        action: Closure<*>
    ) = migrateMappings(environmentId, Action { action.delegatingTo(it) })

    fun migrateMappings(environmentId: String, action: Action<MigrateMappingsTask>) {
        project.extensions.getByType(MigrateExtension::class.java)
            .migrateMappings(environmentId, action)
    }

    /** `unimined.migrate { }` → the `unimined-migrate` extension. */
    fun migrate(action: Action<MigrateExtension>) {
        action.execute(project.extensions.getByType(MigrateExtension::class.java))
    }

    /** Groovy DSL overload; delegates the closure to the migrate extension. */
    fun migrate(
        @DelegatesTo(value = MigrateExtension::class, strategy = Closure.DELEGATE_FIRST)
        action: Closure<*>
    ) {
        val extension = project.extensions.getByType(MigrateExtension::class.java)
        action.delegate = extension
        action.resolveStrategy = Closure.DELEGATE_FIRST
        action.call()
    }

    /** `unimined.remap { }` → the `unimined-remap` extension. */
    fun remap(action: Action<RemapExtension>) {
        action.execute(project.extensions.getByType(RemapExtension::class.java))
    }

    /** Groovy DSL overload; delegates the closure to the remap extension. */
    fun remap(
        @DelegatesTo(value = RemapExtension::class, strategy = Closure.DELEGATE_FIRST)
        action: Closure<*>
    ) {
        val extension = project.extensions.getByType(RemapExtension::class.java)
        action.delegate = extension
        action.resolveStrategy = Closure.DELEGATE_FIRST
        action.call()
    }

    /** `unimined.mod { }` → the `unimined-mod` extension. */
    fun mod(action: Action<ModExtension>) {
        action.execute(project.extensions.getByType(ModExtension::class.java))
    }

    /** Groovy DSL overload; delegates the closure to the mod extension. */
    fun mod(
        @DelegatesTo(value = ModExtension::class, strategy = Closure.DELEGATE_FIRST)
        action: Closure<*>
    ) {
        val extension = project.extensions.getByType(ModExtension::class.java)
        action.delegate = extension
        action.resolveStrategy = Closure.DELEGATE_FIRST
        action.call()
    }

    /**
     * `unimined.loaderApi("fabric", "0.92.2+1.20.1")` — maven
     * coordinates for a loader's api artifact set. Also records the
     * api's modid into `mod { depends }` when the unimined-mod
     * extension is present.
     */
    fun loaderApi(name: String, version: String, mcVersion: String = "", environment: String? = null): Set<String> {
        recordApiDep(name, version, mcVersion)
        return LoaderApi.api(name, version, mcVersion, environment)
    }

    /** `unimined.loaderApiModule("fabric", "fabric-api-base", v)` —
     *  one sub-module coordinate. */
    fun loaderApiModule(name: String, module: String, version: String, mcVersion: String = "", environment: String? = null): String {
        recordApiDep(name, version, mcVersion)
        return LoaderApi.apiModule(name, module, version, mcVersion, environment)
    }

    private fun recordApiDep(name: String, version: String, mcVersion: String) {
        val mod = project.extensions.findByType(ModExtension::class.java) ?: return
        LoaderApi.providers[name]?.dependsSpec(version, mcVersion)?.forEach { (modid, v) ->
            if (!mod.depends.get().containsKey(modid)) mod.depends.put(modid, v)
        }
    }

    /** `unimined.transform { }` → the `unimined-transform` extension. */
    fun transform(action: Action<TransformExtension>) {
        action.execute(project.extensions.getByType(TransformExtension::class.java))
    }

    /** Groovy DSL overload; delegates the closure to the transform extension. */
    fun transform(
        @DelegatesTo(value = TransformExtension::class, strategy = Closure.DELEGATE_FIRST)
        action: Closure<*>
    ) {
        val extension = project.extensions.getByType(TransformExtension::class.java)
        action.delegate = extension
        action.resolveStrategy = Closure.DELEGATE_FIRST
        action.call()
    }

}
