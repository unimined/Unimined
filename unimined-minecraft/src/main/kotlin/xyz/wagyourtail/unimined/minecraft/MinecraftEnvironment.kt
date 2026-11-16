package xyz.wagyourtail.unimined.minecraft

import groovy.lang.MissingMethodException
import groovy.lang.MissingPropertyException
import groovy.lang.Closure
import groovy.lang.DelegatesTo
import xyz.wagyourtail.unimined.api.util.UniminedProblems
import xyz.wagyourtail.unimined.api.util.callDelegate
import xyz.wagyourtail.unimined.api.util.delegatingTo
import xyz.wagyourtail.unimined.api.util.dependencyNotationPredicate
import xyz.wagyourtail.unimined.api.util.failProblem
import xyz.wagyourtail.unimined.api.util.reportProblem
import org.gradle.api.Action
import org.gradle.api.JavaVersion
import org.gradle.api.Named
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.plugins.ExtensionAware
import org.gradle.api.plugins.ExtensionContainer
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import xyz.wagyourtail.unimined.minecraft.internal.env.EnvironmentInheritance
import xyz.wagyourtail.unimined.minecraft.internal.env.parseEnvironmentPath
import xyz.wagyourtail.unimined.minecraft.spi.TransformExtensionFacade
import xyz.wagyourtail.unimined.api.environment.EnvironmentReference
import xyz.wagyourtail.unimined.api.environment.EnvironmentSection
import xyz.wagyourtail.unimined.api.spec.DeclaredAccessFile
import xyz.wagyourtail.unimined.api.spec.LoaderSpec
import xyz.wagyourtail.unimined.api.spec.ProductSpec
import xyz.wagyourtail.unimined.api.spec.Side
import xyz.wagyourtail.unimined.mapping.MappingsSpecImpl
import xyz.wagyourtail.unimined.minecraft.internal.RuntimeLoaderSpec
import xyz.wagyourtail.unimined.minecraft.internal.RuntimeProductSpec
import xyz.wagyourtail.unimined.minecraft.internal.RuntimeVersionSpec
import javax.inject.Inject

/**
 * One Minecraft environment, normally bound to the source set named by
 * [environmentId]. Each instance owns independent version, loader, provider, and
 * mapping declarations.
 */
abstract class MinecraftEnvironment @Inject internal constructor(
    val environmentId: String,
    internal val project: Project,
    objects: ObjectFactory
) : Named, ExtensionAware {

    // `extensions` is supplied by Gradle decoration — envs MUST be
    // created via objects.newInstance so the generated storage is the
    // same one Groovy method dispatch consults (env.jarmod(...)).

    /** Groovy DSL dispatch for extension-registered verbs like
     *  `env.jarmod("0.1.5")` / `env.fg1("1.0.0")`: a non-closure call
     *  `verb(args)` can't reach extension objects' methods through
     *  Gradle's dynamic dispatch, so route it here — a same-named
     *  extension's method wins, otherwise the first extension object
     *  carrying a compatible method (`jarmod`'s extension also serves
     *  `fg1`/`customSearge`). A `verb { }` closure call still
     *  configures the extension directly, so both forms work. 1.x
     *  verbs removed in 2.0 emit migration notes before falling
     *  through to missing-method. */
    fun methodMissing(name: String, args: Any): Any? {
        val argArray = args as Array<*>
        val target = extensions.findByName(name)
        if (target != null) {
            // `Method.invoke` on a Unit/void verb yields null — the
            // null check must wrap the lookup, not the invocation
            val method = methodFor(target, name, argArray)
                ?: fail(name, argArray,
                    "extension exists but no matching overload")
            return method.invoke(target, *argArray)
        }
        // the verb may live on a differently-named extension object —
        // `env.fg1(v)` dispatches to `jarmod`'s `fg1` method
        for (schema in extensions.extensionsSchema.elements) {
            val ext = extensions.findByName(schema.name) ?: continue
            methodFor(ext, name, argArray)
                ?.let { return it.invoke(ext, *argArray) }
        }
        removed1xVerbs[name]?.let { note ->
            project.failProblem(
                "env/removed-1x-verb",
                "unimined: environment '$environmentId' — '$name' " +
                        "was removed in Unimined 2.0",
                note
            )
        }
        pluginVerbs[name]?.let { plugin ->
            project.failProblem(
                "env/verb-plugin-missing",
                "unimined: environment '$environmentId' — verb " +
                        "'$name' is contributed by the '$plugin' " +
                        "plugin, which is not applied",
                "apply `id \"$plugin\"` to use `$name` on " +
                        "environments"
            )
        }
        fail(name, argArray, "no DSL verb or extension")
    }

    /** The [name]-named method on [ext] whose parameter count and
     *  types are assignment-compatible with [argArray] — first match
     *  wins, mirroring Groovy's own overload ordering. */
    private fun methodFor(
        ext: Any, name: String, argArray: Array<*>
    ): java.lang.reflect.Method? = ext.javaClass.methods.filter {
        it.name == name && it.parameterCount == argArray.size
    }.firstOrNull { m ->
        argArray.indices.all { i ->
            argArray[i] == null || m.parameterTypes[i].isInstance(argArray[i])
        }
    }

    /**
     * 1.x env-scoped DSL verbs removed or re-shaped in 2.0 — Groovy
     *  calls on the env hit [methodMissing] and get a migration note
     *  instead of a bare missing-method error. Consulted only after
     *  the named-extension lookup fails, so live extension verbs
     *  (`jarmod`, `forge`, `patchBase`, `jvmdowngrader`,
     *  `expectPlatform`, `devBundleExport`, `transform`) always win.
     */
    private val removed1xVerbs: Map<String, String> = buildMap {
        val loader = { verb: String, id: String ->
            "1.x `$verb { }` patcher blocks became " +
                    "`$verb(\"<version>\")` in 2.0 " +
                    "(equivalently `loader(\"$id\", \"<version>\")`; " +
                    "see docs/migration-1x-to-2.0.md)"
        }
        // loader patcher blocks → loader("name", "version")
        this["fabric"] = loader("fabric", "fabric")
        this["quilt"] = loader("quilt", "quilt")
        this["forge"] = loader("forge", "forge")
        this["minecraftForge"] = loader("minecraftForge", "forge")
        this["neoForge"] = loader("neoForge", "neoforge")
        this["neoForged"] = loader("neoForged", "neoforge")
        this["neoforge"] = loader("neoforge", "neoforge")
        this["cleanroom"] = loader("cleanroom", "cleanroom")
        this["legacyFabric"] = loader("legacyFabric", "legacyfabric")
        this["babric"] = loader("babric", "babric")
        this["ornitheFabric"] = loader("ornitheFabric", "ornithe")
        this["ornithe"] = loader("ornithe", "ornithe")
        this["modloader"] =
            "ModLoader-era mods run under jarmod in 2.0 — " +
                    "`loader(\"jarmod\")` + a " +
                    "`jarMod(\"risugami:modloader:<mc>\")` dependency " +
                    "(auto-patched by jarmod-modloader-fixes)"
        this["risugami"] = this["modloader"]!!
        this["nil"] = "NilLoader has no 2.0 loader descriptor — " +
                "planned, not yet supported"
        this["jarMod"] = loader("jarMod", "jarmod")
        this["foxLoader"] = loader("foxLoader", "foxloader")
        this["liteloader"] = loader("liteloader", "liteloader")
        this["rift"] = loader("rift", "rift")
        this["spigot"] = loader("spigot", "spigot")
        this["paper"] = loader("paper", "paper")
        this["folia"] = "Folia has no 2.0 loader descriptor — " +
                "paper covers Paper; Folia support is planned"
        this["arch"] = "Arch-based loaders have no 2.0 descriptor — " +
                "use a specific loader verb (`forge(\"<version>\")`, " +
                "`fabric(\"<version>\")`, …)"
        // "bukkit" is the family name — craftbukkit is the descriptor
        this["bukkit"] = loader("bukkit", "craftbukkit")
        this["craftBukkit"] = loader("craftBukkit", "craftbukkit")
        this["flint"] =
            "flint loader support was removed — maven.flintloader.net " +
                    "is offline, so `net.flintloader:*` artifacts are " +
                    "unresolvable. Reintroduce via LoaderRegistry + a " +
                    "mirrored repo if the host returns."
        // merged-env patcher block → implicit merge semantics
        this["merged"] =
            "merged-env patching is implicit in 2.0 — declare merges " +
                    "via `combineWith`/`from`; there is no patcher block"
        // standalone access patchers → access { } / file collections
        this["accessWidener"] =
            "declare access-widener files via `access { }` " +
                    "(`from(\"path\")`) or `accessWideners.from(...)` in 2.0"
        this["accessTransformer"] =
            "declare access-transformer files via `access { }` " +
                    "(`from(\"path\")`) or `accessTransformers.from(...)` " +
                    "in 2.0"
        // removed SPI callbacks (docs/migration-1x-to-2.0.md §Removed
        // extension points)
        val spi = "1.x patcher/remapper extension points were removed — " +
                "declare transforms via `transform { }`/transformer " +
                "claims and remap tuning via `remap { }` step specs " +
                "(see docs/migration-1x-to-2.0.md)"
        this["customPatcher"] = spi
        this["mcPatcher"] = spi
        this["minecraftRemapper"] = spi
        this["remapper"] = spi
        this["tinyRemapperConf"] = spi
        this["addResourceRemapper"] = spi
        this["addExtension"] = spi
        // env-nested sections that moved to top level
        this["runs"] =
            "`runs { }` moved to top level in 2.0 (`runs { }` / " +
                    "`unimined.runs { }`) — it is not per-env"
        this["source"] =
            "decompile-engine config moved to `unimined.source { }` " +
                    "(top level) in 2.0"
        // 1.x env remap-task verbs → auto-wired prod tasks / surfaces
        this["remap"] =
            "production remap tasks are auto-wired (`remap<Env>Jar`) — " +
                    "custom artifact remaps go through `unimined.remap { }` " +
                    "surfaces"
        this["remapSources"] =
            "production sources remaps are auto-wired " +
                    "(`remap<Env>SourcesJar`) — see `unimined.remap { }`"
        // 1.x preset name kept on the frontend only
        this["reIndev"] = "`reIndev` is `reindev { }` in 2.0"
        // Property assignments written in call form
        this["version"] = "`version` is a Property — assign with " +
                "`version = \"x\"` or `version.set(\"x\")`"
        this["side"] = "`side` is a Property — assign with " +
                "`side = Side.SERVER` or `side.set(...)`"
    }

    /**
     * Env verbs contributed by optional transform/adapter plugins —
     * when the plugin isn't applied the verb never reaches Groovy
     * dispatch, so name the plugin instead of a bare missing-method
     * error. (`jarmod`'s extension object carries the `fg1`/
     * `customSearge` methods too — one plugin entry covers the set.)
     */
    private val pluginVerbs: Map<String, String> = mapOf(
        "jarmod" to "xyz.wagyourtail.unimined.transform.jarmod",
        "fg1" to "xyz.wagyourtail.unimined.transform.jarmod",
        "customSearge" to "xyz.wagyourtail.unimined.transform.jarmod",
        "patchBase" to "xyz.wagyourtail.unimined.adapter-patchbase",
        "patchBaseCreator" to "xyz.wagyourtail.unimined.adapter-patchbase",
        "jvmdowngrader" to "xyz.wagyourtail.unimined.jvmdg",
        "expectPlatform" to "xyz.wagyourtail.unimined.adapter-expectplatform",
        "devBundleExport" to "xyz.wagyourtail.unimined.provider.devbundle",
        "transform" to "xyz.wagyourtail.unimined.transform",
    )

    /**
     * Groovy property-write bridge: 1.x scalar env properties were
     * plain `var`s — `env.version = "1.2.3"` worked verbatim. 2.0
     * exposes `Property` fields, which Groovy treats as read-only
     * without a generated setter; route the surviving names to
     * `Property.set` and fail removed names with migration notes.
     */
    fun propertyMissing(name: String, value: Any?): Any? {
        when (name) {
            "version" -> {
                version.set(value.toString())
                return null
            }
            "side" -> {
                side.set(
                    when (val v = value.toString().lowercase()) {
                        // 1.x `side "joined"`/`side "combined"` forms
                        "joined", "combined" -> Side.MERGED
                        else -> try {
                            Side.valueOf(v.uppercase())
                        } catch (e: IllegalArgumentException) {
                            throw MissingPropertyException(
                                name, javaClass, e)
                        }
                    })
                return null
            }
        }
        removed1xProperties[name]?.let { note ->
            project.failProblem(
                "env/removed-1x-property",
                "unimined: environment '$environmentId' — '$name' was " +
                        "removed in Unimined 2.0",
                note
            )
        }
        throw MissingPropertyException(name, javaClass)
    }

    /** Read counterpart of [propertyMissing] — Groovy property reads
     *  (`env.mcPatcher`) on removed 1.x members get the same note. */
    fun propertyMissing(name: String): Any? {
        removed1xProperties[name]?.let { note ->
            project.failProblem(
                "env/removed-1x-property",
                "unimined: environment '$environmentId' — '$name' was " +
                        "removed in Unimined 2.0",
                note
            )
        }
        throw MissingPropertyException(name, javaClass)
    }

    /** 1.x env-scoped *properties* removed in 2.0 — Groovy reads and
     *  writes both land in `propertyMissing`. */
    private val removed1xProperties: Map<String, String> = buildMap {
        val remapKnob =
            "production jar wiring is automatic in 2.0 — remap/" +
                    "sources-jar opt-outs live on `mod { }` packaging, " +
                    "not the env"
        this["defaultRemapJar"] =
            "`defaultRemapJar` is a live `Property<Boolean>` in 2.0 — " +
                    "assign with `.set(false)` (`=` writes hit this " +
                    "handler in Groovy)"
        this["defaultRemapSourcesJar"] = remapKnob
        this["createJarTask"] = remapKnob
        this["canCombine"] =
            "merge semantics are fixed in 2.0 — declare merges via " +
                    "`combineWith`/`from`"
        this["useUnionRelaunch"] = "removed in Unimined 2.0 — no " +
                "equivalent (see docs/migration-1x-to-2.0.md)"
        this["onMergeFail"] = "removed in Unimined 2.0 — merge failure " +
                "handling is fixed"
        this["obfuscated"] = "removed in Unimined 2.0 — the provider " +
                "pipeline tracks namespaces internally"
        val internals = "removed in Unimined 2.0 — provider internals; " +
                "no DSL equivalent (see docs/migration-1x-to-2.0.md)"
        this["mcPatcher"] = internals
        this["minecraftRemapper"] = internals
        this["tinyRemapperConf"] = internals
        this["minecraftData"] = internals
        this["minecraftDependency"] = internals
        this["minecraftFileDev"] = internals
        this["minecraftSourceFileDev"] = internals
        this["mergedOfficialMinecraftFile"] = internals
        this["forgeMCVersion"] = internals
        this["isMinecraftJar"] = internals
        this["combinedWithList"] = internals
        this["localCache"] = internals
    }

    private fun fail(
        name: String, argArray: Array<*>, why: String
    ): Nothing {
        project.reportProblem(
            "env/method-missing",
            "unimined: environment '$environmentId' has no method " +
                "'$name'",
            "$why — call was ${name}(" +
                argArray.joinToString { arg ->
                    arg?.toString() ?: "null" } + ")",
            UniminedProblems.Severity.ERROR
        )
        throw MissingMethodException(name, javaClass,
            argArray.filterNotNull().toTypedArray())
    }

    override fun getName(): String = environmentId

    /** Environment this env implicitly inherits sections+output from —
     *  the enclosing configure block's env (top-level `minecraft {}` is
     *  `main`). Set by [sourceSet] at creation; envs created through the
     *  container default to `main`. */
    internal var parentEnvId: String = "main"

    /** Set `false` to opt this env out of implicit parent inheritance. */
    val inheritParent: Property<Boolean> =
        objects.property(Boolean::class.java).convention(true)

    /** Declared datagen envs — consumed by the runs module when it
     *  materializes `runDatagen`-style configs; loader descriptors carry
     *  `datagenMain`/`datagenArgs` capabilities for the launch. */
    val datagenEnvs = mutableListOf<MinecraftEnvironment>()

    /** Declared gametest envs — runs module wires the JUnit launch +
     *  loader junit artifact (e.g. `fabric-loader-junit`) from these. */
    val gametestEnvs = mutableListOf<MinecraftEnvironment>()

    /** `true` for envs declared via [datagen]/[gametest] — auxiliary
     *  source sets whose classes are consumed through the PARENT env's
     *  mod metadata (entrypoints, generated data), never as their own
     *  mod. Mod packaging skips metadata generation for them: a second
     *  same-id `fabric.mod.json` on the classpath would collide with
     *  the parent's. A managed property (not a plain var) so consumers
     *  read the final value at execution — env binding fires before
     *  [datagen]/[gametest] mark the env. */
    val isAuxEnv: Property<Boolean> =
        objects.property(Boolean::class.java).convention(false)

    /** Environment id carrying this env's client split — set on `main`
     *  by [MinecraftExtension.splitEnvironmentSourceSets]. Client-side
     *  runs conventionally bound to this env redirect to the split
     *  client env: this env's artifact is the client∩server
     *  intersection jar (it cannot launch a client), while the client
     *  env carries the full client artifact plus this env's output via
     *  the implicit-parent combine. Wiring consumers (runs, IDE
     *  emitters, packaging) read this lazily — the flag lands during
     *  DSL evaluation, after env binding. */
    val splitClientEnvironment: Property<String> =
        objects.property(String::class.java)

    /** Declare a datagen source-set env ([name] defaults to
     *  `<env>Data` — `fabric` → `fabricData`, `main` → `data`). The env
     *  inherits this env's version/mappings/output via implicit parent
     *  inheritance, and its `src/<name>/generated` dir is added to this
     *  env's resources so generated data lands in the mod jar. The runs
     *  module materializes the actual "Data Generation" run from the
     *  loader descriptor's `datagenMain`/`datagenArgs` capabilities;
     *  the mod still declares its own `fabric-datagen`-style
     *  entrypoint. `client = true` marks a client-side pack. */
    @JvmOverloads
    fun datagen(
        name: String = if (environmentId == "main") "data"
                       else "${environmentId}Data",
        client: Boolean = false,
        action: Action<MinecraftEnvironment> = Action {}
    ): MinecraftEnvironment = sourceSet(name, action).also { env ->
        env.clientDatagen = client
        env.isAuxEnv.set(true)
        datagenEnvs += env
        // loom convention: generated data lands under src/<env>/generated
        // and ships in this env's resources — wire lazily in case the
        // Java plugin's source set binds later
        project.extensions
            .getByType(org.gradle.api.tasks.SourceSetContainer::class.java)
            .named(environmentId) {
                it.resources.srcDir("src/$environmentId/generated")
            }
    }

    /** Groovy DSL overload — `datagen("data", true) { ... }`. */
    @JvmOverloads
    fun datagen(
        name: String = if (environmentId == "main") "data"
                       else "${environmentId}Data",
        client: Boolean = false,
        @DelegatesTo(value = MinecraftEnvironment::class, strategy = Closure.DELEGATE_FIRST)
        action: Closure<*>
    ): MinecraftEnvironment = datagen(name, client, Action { action.delegatingTo(it) })

    /** Declare a gametest source-set env ([name] defaults to
     *  `<env>Gametest`). Inherits like [datagen]; the runs module wires
     *  the loader's JUnit launch artifact + a `Test`-task integration
     *  (gametests orchestrate real game launches per test — see
     *  `fabric-loader-junit`); the mod declares its loader-specific
     *  gametest entrypoint key itself. */
    @JvmOverloads
    fun gametest(
        name: String = if (environmentId == "main") "gametest"
                       else "${environmentId}Gametest",
        action: Action<MinecraftEnvironment> = Action {}
    ): MinecraftEnvironment = sourceSet(name, action).also {
        it.isAuxEnv.set(true)
        gametestEnvs += it
    }

    /** Groovy DSL overload — `gametest("gametest") { ... }`. */
    @JvmOverloads
    fun gametest(
        name: String = if (environmentId == "main") "gametest"
                       else "${environmentId}Gametest",
        @DelegatesTo(value = MinecraftEnvironment::class, strategy = Closure.DELEGATE_FIRST)
        action: Closure<*>
    ): MinecraftEnvironment = gametest(name, Action { action.delegatingTo(it) })

    /** Datagen envs: client-side pack flag (fabric `--client` arg). */
    var clientDatagen: Boolean = false

    /** Nested env whose implicit parent is THIS env — supports
     *  `sourceSet("a") { sourceSet("b") { } }` grouping. */
    @JvmOverloads
    fun sourceSet(
        sourceSetName: String,
        action: Action<MinecraftEnvironment> = Action {}
    ): MinecraftEnvironment =
        project.extensions.getByType(MinecraftExtension::class.java)
            .sourceSetInternal(sourceSetName, environmentId, action)

    /** Groovy DSL overload — `sourceSet("a") { sourceSet("b") { } }`. */
    @JvmOverloads
    fun sourceSet(
        sourceSetName: String,
        @DelegatesTo(value = MinecraftEnvironment::class, strategy = Closure.DELEGATE_FIRST)
        action: Closure<*>
    ): MinecraftEnvironment =
        sourceSet(sourceSetName, Action { action.delegatingTo(it) })

    /** Aux-env section inheritance runs once — [applyImplicitParent]
     *  fires on every [combinedWith] read. */
    private var auxSectionsInherited = false

    /** Apply the recorded implicit-parent combine, unless opted out or
     *  the parent was already combined/copied explicitly. Deferred so
     *  [inheritParent] can be set inside the configure block. */
    internal fun applyImplicitParent() {
        if (environmentId == parentEnvId) return
        if (!inheritParent.get()) return
        val ref = EnvironmentReference(project.path, parentEnvId)
        // SIDE/PROVIDER ride the lazy constructor conventions (same
        // mechanism as VERSION's own provider) — copying them here
        // would install a *later* convention than the transform
        // wirings' bound-env conventions (bukkit → SERVER side, fabric
        // → provider), and the last convention wins. Explicit
        // `combineWith`/`copyFrom` calls still copy all sections.
        if (ref !in inheritance.references) combineWith(
            ref,
            EnvironmentSection.COMBINE_DEFAULT - EnvironmentSection.SIDE -
                EnvironmentSection.PROVIDER,
            false
        )
        // aux envs (datagen/gametest) launch through the parent's
        // loader and test the parent's mods — without LOADER their
        // classpaths never get the loader jar, and without
        // DEPENDENCIES neither the parent's mod buckets (fabric-api
        // etc.) nor its plain library buckets reach the aux env. The
        // access mirrors keep the
        // aux artifact's transform inputs identical to the parent's
        // (a narrower artifact would break gametest/datagen code
        // that relies on parent-declared widening). All of these
        // exclusions exist for neutral cross-env composition; a
        // declared aux env is the same ecosystem by definition.
        // First read is execution-time (validateCombinations/spec),
        // so isAuxEnv is final here.
        if (!isAuxEnv.get() || auxSectionsInherited) return
        auxSectionsInherited = true
        combineWith(
            ref,
            setOf(EnvironmentSection.LOADER, EnvironmentSection.DEPENDENCIES),
            true
        )
        val parent = project.extensions
            .getByType(MinecraftExtension::class.java).environment(ref)
        transitiveAccessWideners.convention(parent.transitiveAccessWideners)
        dependencyInterfaceInjection.convention(parent.dependencyInterfaceInjection)
        patchLvts.convention(parent.patchLvts)
        applyPreen.convention(parent.applyPreen)
        autoNests.convention(parent.autoNests)
        interfaceInjectionScan.convention(parent.interfaceInjectionScan)
        discoverMixinConfigs.convention(parent.discoverMixinConfigs)
        discoverGametestEntrypoints.convention(parent.discoverGametestEntrypoints)
        accessWideners.from(parent.accessWideners)
        accessTransformers.from(parent.accessTransformers)
        consumedAccessDocs.from(parent.consumedAccessDocs)
        inheritedAccessSpecs += parent.inheritedAccessSpecs + parent.accessDsl
        inheritedModSpecs += parent.inheritedModSpecs + parent.mods
        // DEPENDENCIES covers both the mod buckets and the plain
        // sourceSet buckets (implementation/compileOnly/runtimeOnly/
        // api) — see MinecraftEnvironmentCopyHandler.wireDependencies
    }

    /** Environments sharing sections must resolve the same mc version. */
    internal fun checkVersionConflicts() {
        val target = version.orNull ?: return
        for (source in inheritance.references + inheritance.versionCopySources) {
            val other = project.extensions
                .getByType(MinecraftExtension::class.java)
                .environment(source).version.orNull ?: continue
            if (other != target) project.failProblem(
                "env/version-conflict",
                "Minecraft environment '$environmentId' inherits from " +
                    "'${source.projectPath}:${source.environmentId}' " +
                    "(minecraft $other) but declares version $target",
                "environments sharing sections must use the same " +
                    "minecraft version")
        }
    }

    /** Minecraft version id for this source set. Convention inherits the
     *  implicit parent's version lazily — bound handlers can read it
     *  before [applyImplicitParent] runs (env binding fires on
     *  `maybeCreate`, ahead of the configure block, so copy-section
     *  conventions aren't installed yet). An explicit copy (`combineWith`
     *  with VERSION) still replaces this convention. */
    val version: Property<String> = objects.property(String::class.java)
        .convention(project.provider {
            if (environmentId == parentEnvId || !inheritParent.get()) null
            else project.extensions.getByType(MinecraftExtension::class.java)
                .environments.findByName(parentEnvId)?.version?.orNull
        })

    /** Client/server/merged side for this source set. Convention
     *  inherits the implicit parent's side lazily — same mechanism as
     *  [version]: transform wirings install family conventions on bound
     *  envs (bukkit-family → SERVER), and a copy-time convention would
     *  clobber them since the last convention wins. An explicit
     *  `combineWith`/`copyFrom` SIDE still replaces this convention. */
    val side: Property<Side> = objects.property(Side::class.java)
        .convention(project.provider {
            if (environmentId == parentEnvId || !inheritParent.get()) Side.MERGED
            else project.extensions.getByType(MinecraftExtension::class.java)
                .environments.findByName(parentEnvId)?.side?.orNull
                ?: Side.MERGED
        })

    /** Set [side] by name, case-insensitively — `side("client")` ≡
     *  `side = Side.CLIENT`. Handy in Groovy, where
     *  `side = Side.CLIENT` needs the enum on the script classpath;
     *  in Kotlin `side = "client"` is rejected (`side` is a
     *  `Property<Side>`), so the call form is the string route there
     *  too. 1.x `joined`/`combined` are accepted as [Side.MERGED]
     *  aliases, matching [propertyMissing]. */
    fun side(side: String) {
        this.side.set(
            when (side.lowercase()) {
                "joined", "combined" -> Side.MERGED
                else -> Side.entries.firstOrNull {
                    it.name.equals(side, ignoreCase = true)
                } ?: project.failProblem("env/invalid-side",
                    "unimined: environment '$environmentId' — " +
                            "unknown side \"$side\"",
                    "expected one of: joined, combined, " +
                            Side.entries.joinToString {
                                it.name.lowercase()
                            })
            })
    }

    /** Optional provider override for this source set. Convention
     *  inherits the implicit parent's provider lazily — see [side] for
     *  why convention-install order matters. */
    val provider: Property<String> = objects.property(String::class.java)
        .convention(project.provider {
            if (environmentId == parentEnvId || !inheritParent.get()) null
            else project.extensions.getByType(MinecraftExtension::class.java)
                .environments.findByName(parentEnvId)?.provider?.orNull
        })

    /**
     * Product this environment produces, scoped by game family —
     * `minecraft:native` (vanilla, the default), `minecraft:bta`,
     * `minecraft:reindev`, `custom:<name>` for user-supplied dists.
     * Providers key their product tables on this; env binding
     * validates it against the declared loader's `supportedProducts`.
     */
    val product: Property<String> =
        objects.property(String::class.java)
            .convention(ProductSpec.PRODUCT_NATIVE)

    /** API-only mode — transforms that ship a full server artifact
     *  (bukkit family) may read this to compile against the loader's
     *  published api artifact instead of producing the patched
     *  server jar. No effect on transforms that don't check it. */
    val apiOnly: Property<Boolean> = objects.property(Boolean::class.java)

    /** Custom game-provider mode (1.x `customGameProvider`) — adds the
     *  loader's `skipMcProvider` system property to run configs,
     *  suppressing the loader's built-in MC game-provider lookup for
     *  total-conversion setups (typically paired with an `empty-*`
     *  version). Only loaders whose descriptor declares a
     *  `skipMcProvider` capability emit anything. */
    val customGameProvider: Property<Boolean> =
        objects.property(Boolean::class.java).convention(false)

    /** Primary declared modloader for this environment — read by transform
     *  plugins to contribute loader-specific wiring (dependency defaults,
     *  namespace conventions, transformers). The primary carries all of the
     *  environment's loader semantics — production namespace, remap,
     *  metadata emission, and run configs; additional loaders declared by
     *  repeated [loader] calls merge onto the compile classpath only (see
     *  [loaderSpecs]). */
    val loaderSpec: Property<LoaderSpec> = objects.property(LoaderSpec::class.java)

    /** Loaders declared on this environment by `loader(...)`, in
     *  declaration order — the first is the primary ([loaderSpec]). */
    internal val declaredLoaderSpecs = mutableListOf<LoaderSpec>()

    /** LOADER-section copy sources — `combineWith`/`copyFrom` envs whose
     *  non-primary loaders propagate here as compile-classpath
     *  secondaries (resolved lazily in [loaderSpecs]). */
    internal val inheritedLoaderSources =
        linkedSetOf<EnvironmentReference>()

    /**
     * Every loader applying to this environment — the primary
     * ([loaderSpec]) first, then further declared loaders, then
     * LOADER-section-inherited ones. More than one entry = a merged
     * multi-loader environment (the 1.x `merged { fabric; forge }`
     * analog): each loader contributes its product/libraries to the
     * compile classpath, while production, remapping, metadata, and
     * run semantics stay with the primary.
     */
    val loaderSpecs: List<LoaderSpec>
        get() {
            val inherited = inheritedLoaderSources.flatMap { ref ->
                project.extensions.getByType(MinecraftExtension::class.java)
                    .environment(ref).loaderSpecs.drop(1)
            }
            return (listOfNotNull(loaderSpec.orNull) +
                    declaredLoaderSpecs + inherited).distinct()
        }

    /**
     * Base filename for the produced Minecraft artifacts — downstream
     * transforms append `-<key>`/`-<namespace>` suffixes onto it.
     * Loaders override the `minecraft-<env>-<version>` default when a
     * runtime probes the classpath by filename (FML's
     * `FileUtils.matchFileName` searches `contains(<artifact>-<version>)`,
     * so forge-family environments name theirs `forge-<fullVersion>`).
     */
    val artifactBaseName: Property<String> =
        objects.property(String::class.java).convention(
            version.map { "minecraft-$environmentId-$it" })

    /**
     * The namespace this environment compiles and runs against. When unset,
     * the resolved mapping tree's last namespace is used — e.g. `named`
     * for yarn-style chains, `official` for bare mojmap. Read lazily by
     * downstream wiring via `MinecraftExtension.devNamespace`.
     */
    val devNamespace: Property<String> = objects.property(String::class.java)

    /**
     * The namespace jar content arrives at the dev pipeline's core
     * remap step in — normally the source namespace, but loader steps
     * may relocate content mid-pipeline (forge userdev's binpatch
     * substitutes the srg-named clean jar). When unset,
     * `MinecraftExtension.sourceNamespace` applies.
     */
    val devRemapSourceNamespace: Property<String> = objects.property(String::class.java)

    /**
     * Namespaces mod dependencies in this environment arrive in — mods are
     * remapped to [devNamespace]. When unset, the loader plugin's default
     * (`MinecraftExtension.loaderModSourceNamespaces`) applies; fabric-like
     * loaders default to `intermediary`. Auto-wiring supports a single
     * source namespace; for mixed inputs use `remap.remappedViews` directly.
     */
    val modSourceNamespaces: ListProperty<String> = objects.listProperty(String::class.java)

    /**
     * Whether unimined auto-wires `remap<JarTask>` production tasks for
     * this environment's source-set jar tasks (the loom/1.x convention:
     * the input jar gains a `-dev` classifier, the remap task produces
     * the prod-namespace artifact under the original classifier).
     */
    val defaultRemapJar: Property<Boolean> =
        objects.property(Boolean::class.java).convention(true)

    /**
     * Access-widener files applied to this environment's dev Minecraft
     * artifact — populated directly or by loader transforms (fabric-family
     * `*.accesswidener` files from dependent mods are merged in by the
     * loader's wiring).
     */
    val accessWideners: ConfigurableFileCollection = objects.fileCollection()

    /**
     * Whether access wideners declared by compile-classpath mod
     * dependencies contribute their `transitive-*` directives to this
     * environment's dev Minecraft artifact (Loom's
     * `transitiveAccessWideners` toggle; default `true`). Disable to
     * isolate the dev artifact from dependency-declared widening.
     */
    val transitiveAccessWideners: Property<Boolean> =
        objects.property(Boolean::class.java).convention(true)

    /**
     * Whether `injected_interfaces` declared by compile-classpath mod
     * dependencies (fmj `custom.*injected_interfaces`, qmj
     * `quilt_loom.injected_interfaces`, `architectury.common.json`)
     * apply to this environment's dev Minecraft artifact — loom's
     * `interfaceInjection.enableDependencyInterfaceInjection` toggle
     * (default `true`). Independent of [transitiveAccessWideners]:
     * dep-declared interface injection can run without dep-declared
     * access widening, and vice versa.
     */
    val dependencyInterfaceInjection: Property<Boolean> =
        objects.property(Boolean::class.java).convention(true)

    /**
     * Ornithe local-variable-table patching (Ploceus `patchLvts`,
     * default `true`) — when the loader is `ornithe` and the
     * environment's Minecraft version normalizes inside
     * `(0.32.0, 1.8.2-rc.5)` — every alpha/beta build and every
     * release/snapshot before 1.8.2, excluding the 15w14a
     * april-fools build — a `condor` step synthesizes
     * `LocalVariableTable` debug info on the environment artifact so
     * decompilers and Mixin `@Local` captures see real variable slots.
     * Ignored by non-ornithe loaders. Disable via [disableLvtPatch].
     */
    val patchLvts: Property<Boolean> =
        objects.property(Boolean::class.java).convention(true)

    /** Opt out of the auto-applied `condor` LVT pass — Ploceus
     *  `disableLvtPatch()` parity. Only meaningful on `ornithe`
     *  environments. */
    fun disableLvtPatch() = patchLvts.set(false)

    /**
     * Ornithe bridge-method repair (Ploceus applies `preen`
     * unconditionally; default `true`) — when the loader is `ornithe`,
     * a `preen` step splits ProGuard-merged bridge methods on the
     * environment artifact back into `ACC_BRIDGE` delegates so the
     * bytecode shape matches javac output. Ignored by non-ornithe
     * loaders. Disable via [disablePreen].
     */
    val applyPreen: Property<Boolean> =
        objects.property(Boolean::class.java).convention(true)

    /** Opt out of the auto-applied `preen` bridge-method pass. Only
     *  meaningful on `ornithe` environments. */
    fun disablePreen() = applyPreen.set(false)

    /**
     * Ornithe nests auto-application (Ploceus's always-appended
     * `NestsMappingSpec` mapping layer, default `true`) — when the
     * loader is `ornithe`, the latest `net.ornithemc:nests` build
     * published for the environment's Minecraft version + side is
     * contributed to the mapping spec automatically, grafting
     * inner-class metadata through the remap so produced artifacts
     * carry `InnerClasses` attributes. An explicit
     * `mappings { nests(build) }` declaration suppresses the
     * contribution. Disable via [disableNests].
     */
    val autoNests: Property<Boolean> =
        objects.property(Boolean::class.java).convention(true)

    /** Opt out of the auto-contributed `nests` enricher source —
     *  Ploceus `nestsProvider` opt-out parity (declare nothing and
     *  the layer stays inert). Only meaningful on `ornithe`
     *  environments. */
    fun disableNests() = autoNests.set(false)

    /**
     * Inline access declarations — the spec behind `access { }`.
     * Rules are stored semantically and materialized as a generated
     * document in the loader's consumed format, riding the same
     * apply/ship/remap channels as file-declared access files.
     */
    val accessDsl: AccessDslSpec = objects.newInstance(AccessDslSpec::class.java)

    /**
     * Named mod classpath groups — the `mods { "<id>" { } }` container.
     * Each group names the source-set outputs (+ extra files) that make
     * up one logical mod on a dev launch. Runs serialize the selected
     * groups for loaders that group classpath entries by mod:
     * fabric-family → `fabric.classPathGroups` system property,
     * forge-family → `MOD_CLASSES` environment entries
     * (Loom `loom.mods` parity). `RunConfig.loadedMods` selects a
     * subset per run; empty means all declared groups.
     */
    val mods: org.gradle.api.NamedDomainObjectContainer<ModGroupSpec> =
        objects.domainObjectContainer(ModGroupSpec::class.java) { name ->
            objects.newInstance(ModGroupSpec::class.java, name, project)
        }

    /** Mod-group specs inherited from the implicit/copy parent —
     *  parallel to [inheritedAccessSpecs]. Effective groups are
     *  `inheritedModSpecs + mods`. */
    internal val inheritedModSpecs = mutableListOf<ModGroupSpec>()

    /** All mod groups visible to this environment — own declarations
     *  first (a child re-declaring a parent's group name shadows it),
     *  then inherited ones. */
    fun effectiveModGroups(): List<ModGroupSpec> =
        (mods.toList() + inheritedModSpecs).distinctBy { it.name }

    /** `mods { }` DSL verb — declare named mod classpath groups:
     *  `mods { "mymod" { sourceSet("main") } }`. */
    fun mods(
        action: Action<in org.gradle.api.NamedDomainObjectContainer<ModGroupSpec>>
    ) {
        action.execute(mods)
    }

    /** Groovy DSL twin of [mods]. */
    fun mods(
        @DelegatesTo(
            value = org.gradle.api.NamedDomainObjectContainer::class,
            strategy = Closure.DELEGATE_FIRST)
        action: Closure<*>
    ) = action.delegatingTo(mods)

    /** `access { }` DSL verb — declare access directives inline:
     *  `accessible`/`extendable`/`mutable` (+ `transitive-*` twins),
     *  `injectInterface`, `extendEnum`, `at` (FML AT entries),
     *  `fileName` for the merged doc's shipped path. */
    fun access(action: Action<in AccessDslSpec>) {
        action.execute(accessDsl)
    }

    /** Groovy DSL twin of [access]. */
    fun access(
        @DelegatesTo(value = AccessDslSpec::class, strategy = Closure.DELEGATE_FIRST)
        action: Closure<*>
    ) = action.delegatingTo(accessDsl)

    /**
     * Jar path the environment's merged access document ships at —
     * plumbing for `access { fileName = "..." }` (set there in DSL;
     * this property is the task-input channel). A direct set and a
     * spec `fileName` must agree — conflicts error at wiring.
     */
    val accessFileName: Property<String> =
        objects.property(String::class.java)

    /**
     * Format the environment's merged access document emits in —
     * plumbing for `access { format = "..." }`; default is the
     * loader's consumed format (`emitter.accessFormat`). Same
     * must-agree rule as [accessFileName].
     */
    val accessFileFormat: Property<String> =
        objects.property(String::class.java)

    /** `interfaceInjection(target, signature)` — shorthand for
     *  `access { transitiveInjectInterface(...) }` (transitive, so the
     *  injection propagates to dependents like loom's
     *  `injected_interfaces` model). */
    fun interfaceInjection(owner: String, signature: String) =
        accessDsl.transitiveInjectInterface(owner, signature)

    /**
     * Whether compiled mixin classes are scanned for implemented
     * interfaces and the results emitted as generated
     * `inject-interface` rules. Default `false` — opt-in: the scan
     * runs on compiled classes, so it can never provide an injection
     * the project's own sources need to compile; declaring rules via
     * `access { injectInterface(...) }` covers both. Restores the
     * scan for projects that want zero declarations.
     */
    val interfaceInjectionScan: Property<Boolean> =
        objects.property(Boolean::class.java).convention(false)

    /**
     * Whether `*.mixins.json` files discovered in the env's resources
     * auto-fill the generated metadata's `mixins` array
     * (default `true`). Disable to require explicit `mod { }`
     * declaration of every config.
     */
    val discoverMixinConfigs: Property<Boolean> =
        objects.property(Boolean::class.java).convention(true)

    /**
     * Whether compiled classes are scanned for loader gametest
     * implementations and emitted as `fabric-gametest`/
     * `fabric-client-gametest` entrypoints (default `true`). Disable
     * to require explicit entrypoint declaration.
     */
    val discoverGametestEntrypoints: Property<Boolean> =
        objects.property(Boolean::class.java).convention(true)

    /** Access-spec instances this env inherits rules from — parent env
     *  specs contributed by `combineWith`, unioned lazily at
     *  materialization so declarations made later in the parent's DSL
     *  body are still seen. */
    val inheritedAccessSpecs = mutableListOf<AccessDslSpec>()

    /**
     * Access-transformer files applied to this environment's dev
     * Minecraft artifact (Forge/FML-family `accesstransformer.cfg` /
     * `*_at.cfg` files) — populated directly or by loader transforms.
     * ATs apply unconditionally, including ones extracted from mod
     * dependencies — FML has no transitive-gated subset.
     */
    val accessTransformers: ConfigurableFileCollection = objects.fileCollection()

    /**
     * Access documents *generated* for this environment — the
     * `access { }` DSL's materialized doc lands here rather than in
     * [accessWideners]/[accessTransformers]. Consumers (dev-jar apply,
     * packaging, remap access-model) read it alongside the declared
     * collections, but `combineWith` does **not** propagate it —
     * children re-emit the rules from `inheritedAccessSpecs` in their
     * own loader's format, so shipping the parent's generated file
     * downstream would duplicate every rule.
     */
    val generatedAccessFiles: ConfigurableFileCollection = objects.fileCollection()

    /**
     * Access documents consumed from dependency *coordinates* — the
     * resolvable `unimined<Env>AccessTransformer` configuration feeds
     * here (plan §4.1, NG `accessTransformer` / MDG `accessTransformers`
     * parity). Dep-authored docs join the environment's dependency
     * class-tweaker channel: they bind like dep-jar access docs
     * (producer-namespace semantics), are never validated, and are not
     * shipped inside this project's own jar.
     */
    val consumedAccessDocs: ConfigurableFileCollection = objects.fileCollection()

    /** Per-file access-declaration overrides (plan escape hatch) —
     *  files declared via [accessFile] emit as independent conversions
     *  (custom `output`, `declare` suppression, forced `format`)
     *  rather than folding into the canonical merge. */
    val accessFileOverrides: ListProperty<DeclaredAccessFile> = objects
        .listProperty(DeclaredAccessFile::class.java)
        .convention(emptyList())

    /** Declares an access file with per-file packaging control —
     *  [format] forces interpretation (`"aw"`/`"at"`/`"legacy_at"`,
     *  default detect), [output] sets a non-canonical jar path
     *  (still converted to the loader's consumed format), [declare]
     *  `false` keeps it out of generated loader metadata. */
    fun accessFile(
        path: Any,
        format: String? = null,
        output: String? = null,
        declare: Boolean = true
    ) {
        val file = project.files(path).files.singleOrNull()
            ?: project.failProblem(
                "env/access-file",
                "unimined: environment '$environmentId' — " +
                    "accessFile($path) must resolve to exactly one file")
        accessFileOverrides.add(
            DeclaredAccessFile(
                file.absolutePath, format, output, declare))
    }

    /** Configuring twin of [accessFile]. */
    fun accessFile(path: Any, action: Action<AccessFileSpec>) {
        val spec = AccessFileSpec()
        action.execute(spec)
        accessFile(path, spec.format, spec.output, spec.declare)
    }

    /** Groovy DSL twin of [accessFile]. */
    fun accessFile(
        path: Any,
        @DelegatesTo(
            target = "accessFileSpec",
            strategy = Closure.DELEGATE_FIRST) action: Closure<*>
    ) {
        val spec = AccessFileSpec()
        action.delegatingTo(spec)
        accessFile(path, spec.format, spec.output, spec.declare)
    }

    /** Mutable backing spec for the configuring [accessFile] verb. */
    class AccessFileSpec {
        var format: String? = null
        var output: String? = null
        var declare: Boolean = true
    }

    /**
     * Predicates consulted when the env's provider-declared MC
     * libraries populate `minecraftLibraries`/`minecraftDeclaredLibraries`
     * — a dep is kept only when every filter accepts it. Transform
     * wiring contributes exclusions (e.g. FG1-era FML dropping
     * `org.ow2.asm` because it supplies its own `asm-all`).
     */
    val libraryFilters: MutableList<(org.gradle.api.artifacts.Dependency) -> Boolean> =
        mutableListOf()

    /** Contribute a library keep-filter — see [libraryFilters]. */
    fun libraryFilter(filter: (org.gradle.api.artifacts.Dependency) -> Boolean) {
        libraryFilters += filter
    }

    /** Groovy DSL overload — `libraryFilter { it.group == "com.example" }`. */
    fun libraryFilter(filter: Closure<Boolean>) =
        libraryFilter { dep -> filter.call(dep) }

    /**
     * Per-dependency force-remap filters for this environment's `mod*`
     * buckets — the user-side escape of `Fabric-Loom-Remap:true` for
     * deps too plain to self-describe (unmarked jars referencing
     * mapped classes where the `refs:` scan can't see them). Each
     * filter is a `(Dependency) -> Boolean` evaluated against the mod
     * buckets' declared dependencies; matching deps' own artifacts
     * (never their transitives) remap unconditionally, bypassing the
     * marker scan.
     */
    val forceRemapFilters: MutableList<(org.gradle.api.artifacts.Dependency) -> Boolean> =
        mutableListOf()

    /** Force-remap `mod*` deps matching [filter] — see [forceRemapFilters]. */
    fun forceRemap(filter: (org.gradle.api.artifacts.Dependency) -> Boolean) {
        forceRemapFilters += filter
    }

    /** Groovy DSL overload — `forceRemap { it.group == "com.example" }`. */
    fun forceRemap(filter: Closure<Boolean>) =
        forceRemap { dep -> filter.call(dep) }

    /** Force-remap `mod*` deps by `group:name[:version]` coordinate —
     *  malformed notation fails with an actionable error naming this
     *  environment (see [dependencyNotationPredicate]). */
    fun forceRemap(notation: String) =
        forceRemap(
            dependencyNotationPredicate(
                notation,
                "forceRemap on minecraft environment '$environmentId'"
            )
        )

    /** Dependency bucket for the generated Minecraft artifact stub. */
    val minecraftConfigurationName: String
        get() = if (environmentId == "main") {
            "minecraft"
        } else {
            "${environmentId}Minecraft"
        }

    /** Dependency bucket for the environment's resolved MC library deps. */
    val minecraftLibrariesConfigurationName: String
        get() = if (environmentId == "main") {
            "minecraftLibraries"
        } else {
            "${environmentId}MinecraftLibraries"
        }

    /** Resolvable view producing the raw (source-namespace) MC artifact. */
    val minecraftRawViewName: String
        get() = if (environmentId == "main") {
            "minecraftRaw"
        } else {
            "${environmentId}MinecraftRaw"
        }

    /** Resolvable view producing the dev-namespace MC artifact. */
    val minecraftDevViewName: String
        get() = if (environmentId == "main") {
            "minecraftDev"
        } else {
            "${environmentId}MinecraftDev"
        }

    /** The provider-declared library set only (version-json deps — no
     *  user/transform contributions); feeds the merge strip's
     *  bundled-library membership. */
    val minecraftDeclaredLibrariesConfigurationName: String
        get() = if (environmentId == "main") {
            "minecraftDeclaredLibraries"
        } else {
            "${environmentId}MinecraftDeclaredLibraries"
        }

    /** Resolvable view producing the production-namespace MC artifact —
     *  the `prodClient`/`prodServer` launch classpath entry and the
     *  mod-view remap-analysis classpath entry. "Prod" means the
     *  namespace shipped mod jars are mapped to, which for runtime-deobf
     *  loaders (FG2-era forge) is MC's *post-deobf* state, not the
     *  literal jar Mojang ships. */
    val minecraftProdViewName: String
        get() = if (environmentId == "main") {
            "minecraftProd"
        } else {
            "${environmentId}MinecraftProd"
        }

    /** The MC artifact view `prodClient`/`prodServer` put on the
     *  launch classpath — [minecraftProdViewName] by default (MC in
     *  the mod-ship namespace); loader wiring overrides where
     *  production launches on the shipped artifact plus a runtime
     *  remap (FG2-era forge: raw obf jar + FMLDeobfTweaker → searge).
     *  Wiring surface, not user DSL — set during environment wiring,
     *  read at run-task realization. */
    var prodRunViewName: String = minecraftProdViewName

    /** Resolvable+declarable conf for coordinate-consumed access docs
     *  (`unimined<Env>AccessTransformer("g:a:v")` — plan §4.1, NG
     *  `accessTransformer` parity). */
    val accessTransformerConfName: String
        get() = "unimined${environmentId.replaceFirstChar(Char::uppercase)}AccessTransformer"

    /** Consumable conf carrying this env's own access docs on the
     *  `unimined<Env>` component (plan §4.1, NG `accessTransformerElements`
     *  parity). */
    val accessTransformerElementsConfName: String
        get() = "unimined${environmentId.replaceFirstChar(Char::uppercase)}AccessTransformerElements"

    /** Dependency bucket for mods used to compile this environment. */
    val modImplementationConfigurationName: String
        get() = if (environmentId == "main") {
            "modImplementation"
        } else {
            "${environmentId}ModImplementation"
        }

    /** Dependency bucket for mods used only at runtime in this environment. */
    val modRuntimeOnlyConfigurationName: String
        get() = if (environmentId == "main") {
            "modRuntimeOnly"
        } else {
            "${environmentId}ModRuntimeOnly"
        }

    /**
     * Dependency bucket for jars embedded into the production artifact —
     * the `include`/`nestJars` mechanism (1.x parity). Entries land under
     * `META-INF/jars`/`META-INF/jarjar` per the env's `nestedJars` format
     * binding, with loader metadata updated to match. Note this bucket is
     * NOT also a mod classpath dep — declare `modImplementation` (or
     * `implementation` for library jarjar deps) alongside when dev runs
     * need the contents.
     */
    val includeConfigurationName: String
        get() = if (environmentId == "main") {
            "include"
        } else {
            "${environmentId}Include"
        }

    /**
     * Classes and resources from environments passed to [combineWith]. The
     * future mod packaging plugin consumes this collection; it is exposed so
     * integrations do not need to rediscover compose requests.
     */
    val combinedOutputs: ConfigurableFileCollection = objects.fileCollection()

    /** Source sets from [combineWith], for generated source jars. */
    val combinedSources: ConfigurableFileCollection = objects.fileCollection()

    /**
     * Transform-contributed dev-sources override — when present
     * (e.g. a forge-userdev mcpconfig chain that produces sources
     * directly), `genSources` consumes this jar instead of running the
     * decompiler, remapping it from [devSourcesNamespace] to the dev
     * namespace. Absent by default; transforms set it lazily.
     */
    val devSources: RegularFileProperty = objects.fileProperty()

    /** Namespace of [devSources]' contents (e.g. `searge`/`mojmap`). */
    val devSourcesNamespace: Property<String> = objects.property(String::class.java)

    /** Environments whose source-set output is composed into this one. */
    val combinedWith: Set<EnvironmentReference>
        get() {
            // materialized on first read, not at bind — env binding
            // fires on maybeCreate before the configure block runs, so
            // `inheritParent` can't be read in bindSourceSet
            applyImplicitParent()
            return inheritance.references
        }

    /** Copy/combine bookkeeping + composition wiring — see
     *  [EnvironmentInheritance]. Public `copyFrom`/`combineWith` verbs
     *  delegate here. */
    internal val inheritance = EnvironmentInheritance(this)

    /**
     * This environment's declared mapping sources — the object
     * [mappings] configures. Exposed so transform plugins can contribute
     * `defaultSources` (e.g. fabric's intermediary hop).
     */
    val mappingsSpec = MappingsSpecImpl(objects, version, side)

    /**
     * Configure this environment's modloader, letting the loader
     * descriptor resolve the version — currently only `foxloader`,
     * which picks the newest release pairing with the dist via the
     * FoxLoader version map.
     */
    fun loader(name: String) {
        // in a named env a single arg that isn't the env's own name is
        // the version — `sourceSet("fabric") { loader("0.20.1") }` reads
        // as `loader("fabric", "0.20.1")`; env "main" keeps it as a name
        declareLoader(
            if (environmentId == "main" || name == environmentId)
                RuntimeLoaderSpec(name, "")
            else {
                // `loader("quilt")` inside env "fabric" is almost
                // certainly a two-arg typo — the single arg is the
                // version slot, the env id is the inferred loader name
                project.reportProblem(
                    "env/loader-single-arg",
                    "unimined: environment '$environmentId' — the single " +
                        "loader() arg is the version slot",
                    "loader($name) resolves as loader " +
                        "$environmentId:$name; for a different loader " +
                        "use loader(name, version)"
                )
                RuntimeLoaderSpec(environmentId, name)
            }
        )
    }

    /**
     * Configure this environment's modloader and version. The first
     * `loader(...)` declaration is the environment's primary loader —
     * it owns production namespace, remapping, metadata emission, and
     * run configs. Further `loader(...)` calls declare a merged
     * multi-loader environment (1.x `merged { }` parity): each
     * additional loader merges its product onto this environment's
     * compile classpath so one source set can compile against several
     * loaders' APIs; it does not merge client/server distributions
     * ([Side.MERGED] is the other axis).
     */
    fun loader(name: String, version: String) {
        declareLoader(RuntimeLoaderSpec(name, version))
    }

    /** Record a `loader(...)` declaration — the first becomes the
     *  primary ([loaderSpec]); later declarations join [loaderSpecs]
     *  as compile-classpath secondaries. */
    private fun declareLoader(spec: RuntimeLoaderSpec) {
        declaredLoaderSpecs += spec
        if (declaredLoaderSpecs.size == 1) loaderSpec.set(spec)
    }

    /**
     * Groovy DSL — `loader "forge", "47.2.0", { nestedJars { } }`:
     * configure the loader plus transform-side packaging/feature
     * knobs for this environment in one place. The closure's delegate
     * is the transform module's per-env spec, resolved via the
     * `uniminedTransform` extension. Kotlin DSL gets the typed
     * `Action<TransformEnvironmentSpec>` overload as an extension
     * function from the transform module.
     */
    fun loader(
        name: String,
        version: String,
        action: Closure<*>
    ) {
        loader(name, version)
        action.delegatingTo(transformSpec())
    }

    /** Groovy DSL overload — `loader "forge", { nestedJars { } }`. */
    fun loader(name: String, action: Closure<*>) {
        loader(name)
        action.delegatingTo(transformSpec())
    }

    // region named loader verbs — 1.x spellings restored as member
    // sugar: `fabric("0.16.0")` == `loader("fabric", "0.16.0")`, so
    // repeated named declarations merge loaders the same way
    // `loader(...)` does. The `(version, Closure)` twin configures
    // the transform spec, mirroring `loader(name, version, Closure)`;
    // Kotlin DSL gets typed `Action<TransformEnvironmentSpec>` twins
    // from the transform module's `LoaderEnvDsl.kt`. A bare
    // `verb { }` (no version) still routes through [methodMissing]
    // to a same-named env extension or a migration note.

    /** Declares the Fabric loader — `loader("fabric", version)`. */
    fun fabric(version: String) = loader("fabric", version)

    /** Groovy spec-config twin — `fabric "0.16.0", { nestedJars { } }`. */
    fun fabric(version: String, action: Closure<*>) =
        loader("fabric", version, action)

    /** Declares the Quilt loader — `loader("quilt", version)`. */
    fun quilt(version: String) = loader("quilt", version)

    /** Groovy spec-config twin — `quilt "0.26.0", { }`. */
    fun quilt(version: String, action: Closure<*>) =
        loader("quilt", version, action)

    /** Declares the Forge loader — `loader("forge", version)`. */
    fun forge(version: String) = loader("forge", version)

    /** Groovy spec-config twin — `forge "47.2.0", { }`. */
    fun forge(version: String, action: Closure<*>) =
        loader("forge", version, action)

    /** Declares the LexForge loader — `loader("lexforge", version)`. */
    fun lexforge(version: String) = loader("lexforge", version)

    /** Groovy spec-config twin — `lexforge "47.1.0", { }`. */
    fun lexforge(version: String, action: Closure<*>) =
        loader("lexforge", version, action)

    /** Declares the NeoForge loader — `loader("neoforge", version)`. */
    fun neoforge(version: String) = loader("neoforge", version)

    /** Groovy spec-config twin — `neoforge "21.0.0", { }`. */
    fun neoforge(version: String, action: Closure<*>) =
        loader("neoforge", version, action)

    /** 1.x spelling — `loader("neoforge", version)`. */
    fun neoForge(version: String) = neoforge(version)

    /** Groovy spec-config twin — `neoForge "21.0.0", { }`. */
    fun neoForge(version: String, action: Closure<*>) =
        neoforge(version, action)

    /** 1.x spelling — `loader("neoforge", version)`. */
    fun neoForged(version: String) = neoforge(version)

    /** Groovy spec-config twin — `neoForged "21.0.0", { }`. */
    fun neoForged(version: String, action: Closure<*>) =
        neoforge(version, action)

    /** 1.x spelling — `loader("forge", version)`. */
    fun minecraftForge(version: String) = forge(version)

    /** Groovy spec-config twin — `minecraftForge "47.2.0", { }`. */
    fun minecraftForge(version: String, action: Closure<*>) =
        forge(version, action)

    /** Declares the Cleanroom loader — `loader("cleanroom", version)`. */
    fun cleanroom(version: String) = loader("cleanroom", version)

    /** Groovy spec-config twin — `cleanroom "0.4.0", { }`. */
    fun cleanroom(version: String, action: Closure<*>) =
        loader("cleanroom", version, action)

    /** Declares the LegacyFabric loader —
     *  `loader("legacyfabric", version)`. */
    fun legacyfabric(version: String) = loader("legacyfabric", version)

    /** Groovy spec-config twin — `legacyfabric "0.16.0", { }`. */
    fun legacyfabric(version: String, action: Closure<*>) =
        loader("legacyfabric", version, action)

    /** 1.x spelling — `loader("legacyfabric", version)`. */
    fun legacyFabric(version: String) = legacyfabric(version)

    /** Groovy spec-config twin — `legacyFabric "0.16.0", { }`. */
    fun legacyFabric(version: String, action: Closure<*>) =
        legacyfabric(version, action)

    /** Declares the Babric loader — `loader("babric", version)`. */
    fun babric(version: String) = loader("babric", version)

    /** Groovy spec-config twin — `babric "0.16.0", { }`. */
    fun babric(version: String, action: Closure<*>) =
        loader("babric", version, action)

    /** Declares the Ornithe loader — `loader("ornithe", version)`. */
    fun ornithe(version: String) = loader("ornithe", version)

    /** Groovy spec-config twin — `ornithe "0.19.5", { }`. */
    fun ornithe(version: String, action: Closure<*>) =
        loader("ornithe", version, action)

    /** 1.x spelling — `loader("ornithe", version)`. */
    fun ornitheFabric(version: String) = ornithe(version)

    /** Groovy spec-config twin — `ornitheFabric "0.19.5", { }`. */
    fun ornitheFabric(version: String, action: Closure<*>) =
        ornithe(version, action)

    /** Declares the JarMod loader — `loader("jarmod", version)`. */
    fun jarmod(version: String) = loader("jarmod", version)

    /** Groovy spec-config twin — `jarmod "0.1.5", { }`. */
    fun jarmod(version: String, action: Closure<*>) =
        loader("jarmod", version, action)

    /** 1.x spelling — `loader("jarmod", version)`. */
    fun jarMod(version: String) = jarmod(version)

    /** Groovy spec-config twin — `jarMod "0.1.5", { }`. */
    fun jarMod(version: String, action: Closure<*>) =
        jarmod(version, action)

    /** Declares an FG1-era Forge env — `loader("fg1", version)`. */
    fun fg1(version: String) = loader("fg1", version)

    /** Groovy spec-config twin — `fg1 "1.0.0", { }`. */
    fun fg1(version: String, action: Closure<*>) =
        loader("fg1", version, action)

    /** Declares the FoxLoader loader — `loader("foxloader", version)`. */
    fun foxloader(version: String) = loader("foxloader", version)

    /** Version-omitting form — the FoxLoader descriptor resolves the
     *  newest release pairing with the dist. */
    fun foxloader() = loader("foxloader")

    /** Groovy spec-config twin — `foxloader "2.0", { }`. */
    fun foxloader(version: String, action: Closure<*>) =
        loader("foxloader", version, action)

    /** Groovy spec-config twin — `foxloader { }` is live (unlike other
     *  verbs' bare-closure form) because FoxLoader resolves its own
     *  version, mirroring `loader("foxloader") { }`. */
    fun foxloader(action: Closure<*>) = loader("foxloader", action)

    /** 1.x spelling — `loader("foxloader", version)`. */
    fun foxLoader(version: String) = foxloader(version)

    /** Version-omitting form — `loader("foxloader")`. */
    fun foxLoader() = foxloader()

    /** Groovy spec-config twin — `foxLoader "2.0", { }`. */
    fun foxLoader(version: String, action: Closure<*>) =
        foxloader(version, action)

    /** Groovy spec-config twin — `loader("foxloader") { }`. */
    fun foxLoader(action: Closure<*>) = foxloader(action)

    /** Declares the LiteLoader loader —
     *  `loader("liteloader", version)`. */
    fun liteloader(version: String) = loader("liteloader", version)

    /** Groovy spec-config twin — `liteloader "1.12.2", { }`. */
    fun liteloader(version: String, action: Closure<*>) =
        loader("liteloader", version, action)

    /** Declares the Rift loader — `loader("rift", version)`. */
    fun rift(version: String) = loader("rift", version)

    /** Groovy spec-config twin — `rift "1.0.4", { }`. */
    fun rift(version: String, action: Closure<*>) =
        loader("rift", version, action)

    /** Declares the Spigot loader — `loader("spigot", version)`. */
    fun spigot(version: String) = loader("spigot", version)

    /** Groovy spec-config twin — `spigot "1.20.1", { }`. */
    fun spigot(version: String, action: Closure<*>) =
        loader("spigot", version, action)

    /** Declares the Paper loader — `loader("paper", version)`. */
    fun paper(version: String) = loader("paper", version)

    /** Groovy spec-config twin — `paper "1.20.1", { }`. */
    fun paper(version: String, action: Closure<*>) =
        loader("paper", version, action)

    /** Declares the CraftBukkit loader —
     *  `loader("craftbukkit", version)`. */
    fun craftbukkit(version: String) = loader("craftbukkit", version)

    /** Groovy spec-config twin — `craftbukkit "1.20.1", { }`. */
    fun craftbukkit(version: String, action: Closure<*>) =
        loader("craftbukkit", version, action)

    /** 1.x spelling — `loader("craftbukkit", version)`. */
    fun craftBukkit(version: String) = craftbukkit(version)

    /** Groovy spec-config twin — `craftBukkit "1.20.1", { }`. */
    fun craftBukkit(version: String, action: Closure<*>) =
        craftbukkit(version, action)

    /** Family-name shorthand — `loader("craftbukkit", version)`;
     *  `spigot`/`paper` have their own descriptors. */
    fun bukkit(version: String) = craftbukkit(version)

    /** Groovy spec-config twin — `bukkit "1.20.1", { }`. */
    fun bukkit(version: String, action: Closure<*>) =
        craftbukkit(version, action)

    // endregion

    /** Groovy DSL — `transform { nestedJars { } }`: configures this
     *  env's transform spec. The Kotlin counterpart is the transform
     *  module's `transform` extension function. */
    fun transform(action: Closure<*>) =
        action.delegatingTo(transformSpec())

    /** The transform module's per-env spec — `Any` here because the
     *  concrete type (`TransformEnvironmentSpec`) is not visible to
     *  this module; the transform plugin's Kotlin-side `loader` and
     *  `transform` extensions cast it. Normally attached by transform
     *  wiring as the named `transform` extension on this env (envs
     *  are [ExtensionAware]); self-attaches here for envs whose DSL
     *  runs before environment binding. */
    fun transformSpec(): Any {
        extensions.findByName("transform")?.let { return it }
        val ext = project.extensions.findByName("uniminedTransform")
            as? TransformExtensionFacade
            ?: project.failProblem(
                "env/transform-extension-missing",
                "unimined: environment '$environmentId' — transform { } " +
                    "and loader(...) { } require the unimined-transform " +
                    "plugin (uniminedTransform extension)"
            )
        return ext.environmentSpec(environmentId).also { spec ->
            extensions.add("transform", spec)
        }
    }

    /** Configure this environment's mapping sources. */
    fun mappings(action: Action<MappingsSpecImpl>) {
        action.execute(mappingsSpec)
    }

    /** Groovy DSL overload for this environment's mapping sources. */
    fun mappings(
        @DelegatesTo(value = MappingsSpecImpl::class, strategy = Closure.DELEGATE_FIRST)
        action: Closure<*>
    ) {
        action.delegate = mappingsSpec
        action.resolveStrategy = Closure.DELEGATE_FIRST
        action.callDelegate()
    }

    /**
     * Reuse all default configuration sections from [path] without composing
     * its compiled output. Use `sourceSetName` for this project or
     * `projectPath:sourceSetName` for another project.
     */
    fun copyFrom(path: String) {
        copySections(parseEnvironmentPath(path), EnvironmentSection.COPY_DEFAULT, false)
    }

    /** Reuse selected configuration sections from [path]. */
    fun copyFrom(path: String, vararg sections: EnvironmentSection) {
        require(sections.isNotEmpty()) { "copyFrom requires at least one section" }
        copySections(parseEnvironmentPath(path), sections.toSet(), true)
    }

    /** Reuse all default sections from [sourceSet] in this project. */
    fun copyFrom(sourceSet: SourceSet) {
        copyFrom(project, sourceSet)
    }

    /** Reuse selected sections from [sourceSet] in this project. */
    fun copyFrom(sourceSet: SourceSet, vararg sections: EnvironmentSection) {
        copyFrom(project, sourceSet, *sections)
    }

    /** Reuse all default sections from another project's [sourceSet]. */
    fun copyFrom(project: Project, sourceSet: SourceSet) {
        copySections(
            EnvironmentReference(project.path, sourceSet.name),
            EnvironmentSection.COPY_DEFAULT,
            false
        )
    }

    /** Reuse selected sections from another project's [sourceSet]. */
    fun copyFrom(project: Project, sourceSet: SourceSet, vararg sections: EnvironmentSection) {
        require(sections.isNotEmpty()) { "copyFrom requires at least one section" }
        copySections(
            EnvironmentReference(project.path, sourceSet.name),
            sections.toSet(),
            true
        )
    }

    /** Compatibility alias for [copyFrom] using all default sections. */
    fun from(path: String) = copyFrom(path)

    /** Compatibility alias for [copyFrom] using all default sections. */
    fun from(sourceSet: SourceSet) = copyFrom(sourceSet)

    /** Compatibility alias for cross-project [copyFrom]. */
    fun from(project: Project, sourceSet: SourceSet) = copyFrom(project, sourceSet)

    /**
     * Compose [path]'s source-set output into this environment's compile and
     * runtime classpaths, and retain it as a packaged mod input.
     *
     * By default this copies common environment sections but not the source
     * environment's loader. Request [EnvironmentSection.LOADER] explicitly to
     * inherit that section too.
     */
    fun combineWith(path: String) {
        combineWith(parseEnvironmentPath(path), EnvironmentSection.COMBINE_DEFAULT, false)
    }

    /** Compose output and copy selected sections from [path]. */
    fun combineWith(path: String, vararg sections: EnvironmentSection) {
        require(sections.isNotEmpty()) { "combineWith requires at least one section" }
        combineWith(parseEnvironmentPath(path), sections.toSet(), true)
    }

    /** Compose output from [sourceSet] in this project. */
    fun combineWith(sourceSet: SourceSet) {
        combineWith(project, sourceSet)
    }

    /** Compose output and copy selected sections from [sourceSet]. */
    fun combineWith(sourceSet: SourceSet, vararg sections: EnvironmentSection) {
        combineWith(project, sourceSet, *sections)
    }

    /** Compose output from another project's [sourceSet]. */
    fun combineWith(project: Project, sourceSet: SourceSet) {
        combineWith(
            EnvironmentReference(project.path, sourceSet.name),
            EnvironmentSection.COMBINE_DEFAULT,
            false
        )
    }

    /** Compose output and copy selected sections from another project's [sourceSet]. */
    fun combineWith(project: Project, sourceSet: SourceSet, vararg sections: EnvironmentSection) {
        require(sections.isNotEmpty()) { "combineWith requires at least one section" }
        combineWith(
            EnvironmentReference(project.path, sourceSet.name),
            sections.toSet(),
            true
        )
    }

    /** Merge keep-override entry globs — server-jar entries matching
     *  are never stripped as bundled-library copies (the 1.x
     *  `includeGlobs` escape hatch, minus its whitelist semantics —
     *  stripping is membership-based now). */
    val mergeIncludeGlobs: ListProperty<String> = objects
        .listProperty(String::class.java).convention(emptyList())

    /** Extra entry globs stripped from the *server* jar during merge —
     *  for bundled content absent from `minecraftLibraries`
     *  (undeclared embedded deps). */
    val mergeExcludeGlobs: ListProperty<String> = objects
        .listProperty(String::class.java).convention(emptyList())

    /** Master switch for the server-bundled-library strip — pre-1.13
     *  server jars embed their runtime libraries inline; entries
     *  whose paths also exist in a `minecraftLibraries` jar are
     *  dropped from the merged artifact. */
    val stripBundledServerLibraries: Property<Boolean> = objects
        .property(Boolean::class.java).convention(true)

    /** Side-marker annotations stamped on single-side class/field/
     *  method content in the merged artifact (1.x `merger` callback
     *  parity — loaders read these at runtime: fabric `Environment`,
     *  FML-era `SideOnly`). Loader wiring contributes era-correct
     *  markers (forge detects from the loader jar); users may add
     *  their own. */
    val sideMarkers: ListProperty<xyz.wagyourtail.unimined.minecraft
            .merge.JarMerger.SideMarker> = objects
        .listProperty(xyz.wagyourtail.unimined.minecraft.merge
            .JarMerger.SideMarker::class.java)
        .convention(emptyList())

    /** Loader-contributed attributes merged into the environment jar's
     *  manifest at packaging time (1.x `jarTask.manifest` parity —
     *  ornithe's `Calamus-Generation`, legacyfabric's
     *  `Legacy-Fabric-Intermediary-Version`, …). Transform wiring
     *  writes loader facts; packaging folds them into the emitted
     *  manifest merge file so identity and remapped envs behave
     *  identically. */
    val manifestAttributes: MapProperty<String, String> = objects
        .mapProperty(String::class.java, String::class.java)
        .convention(emptyMap())

    /** Typed library-version substitutions forwarded to the provider:
     *  `(coordPrefix, version)` — a version-JSON library whose
     *  `group:name` starts with the prefix gets its version replaced
     *  (1.x `replaceLibraryVersion`; e.g. the LWJGL2 patched-build
     *  swap legacy loaders need on modern JVMs). */
    val librarySubstitutions = mutableListOf<Pair<String, String>>()

    /** Rich library rules (1.x `replaceLibraryVersion(group, name,
     *  classifier) { version }`) — evaluated by
     *  [xyz.wagyourtail.unimined.api.spec.applyLibraryRules] before the
     *  simple [librarySubstitutions] pairs. */
    val libraryRules =
        mutableListOf<xyz.wagyourtail.unimined.api.spec.LibraryRule>()

    /** Whole-coord rewrite filters — the 1.x string form of
     *  `libraryFilter`: each maps `group:name:version[:classifier]` to
     *  a replacement coord or `null` to drop. Named differently from
     *  [libraryFilter] because Kotlin can't overload two bare-lambda
     *  parameters. */
    val libraryCoordFilters = mutableListOf<(String) -> String?>()

    /**
     * Rewrite or drop version-JSON libraries — 1.x
     * `replaceLibraryVersion`. `group`/`name`/`classifier` are full-
     * match regexes; [version] receives the matched artifact's version
     * and returns the replacement — `null` drops the library.
     */
    fun replaceLibraryVersion(
        @org.intellij.lang.annotations.Language("regex") group: String = ".*",
        @org.intellij.lang.annotations.Language("regex") name: String = ".*",
        @org.intellij.lang.annotations.Language("regex") classifier: String = ".*",
        version: (String) -> String?
    ) {
        libraryRules += xyz.wagyourtail.unimined.api.spec.LibraryRule(
            Regex(group), Regex(name), Regex(classifier), version)
    }

    /** Fixed-version overload of [replaceLibraryVersion]. */
    fun replaceLibraryVersion(
        @org.intellij.lang.annotations.Language("regex") group: String = ".*",
        @org.intellij.lang.annotations.Language("regex") name: String = ".*",
        @org.intellij.lang.annotations.Language("regex") classifier: String = ".*",
        version: String?
    ) = replaceLibraryVersion(group, name, classifier) { version }

    /** Groovy-DSL overload of [replaceLibraryVersion] — the closure
     *  receives the matched version string. */
    fun replaceLibraryVersion(
        @org.intellij.lang.annotations.Language("regex") group: String = ".*",
        @org.intellij.lang.annotations.Language("regex") name: String = ".*",
        @org.intellij.lang.annotations.Language("regex") classifier: String = ".*",
        version: Closure<String?>
    ) = replaceLibraryVersion(group, name, classifier) { version.call(it) }

    /** Whole-coord library rewrite — 1.x `libraryFilter(String)`:
     *  return the replacement coord, `null` to drop, or the input
     *  unchanged. */
    fun libraryCoordFilter(filter: (String) -> String?) {
        libraryCoordFilters += filter
    }

    /** Groovy-DSL overload of [libraryCoordFilter]. */
    fun libraryCoordFilter(filter: Closure<String?>) =
        libraryCoordFilter { filter.call(it) }

    /** The `lwjgl3 { }` spec — see [Lwjgl3Spec]. Inert until the
     *  verb runs ([Lwjgl3Spec.enabled]). */
    val lwjgl3: Lwjgl3Spec = objects.newInstance(Lwjgl3Spec::class.java)

    /** Whether the lwjgl2 coord-drop filter has been contributed —
     *  installed once on the first [lwjgl3] call; the filter reads the
     *  final [Lwjgl3Spec.enabled] flag at resolution. */
    private var lwjgl3FilterInstalled = false

    /**
     * Swap the environment's lwjgl2 bindings for lwjgl3 — the
     * RetroFuturaGradle `lwjgl3*` parity surface for running old
     * Minecraft on modern JVMs. Drops every `org.lwjgl.lwjgl:*`
     * library (jars and natives entries) through [libraryCoordFilters]
     * and puts `org.lwjgl:lwjgl` + the declared [Lwjgl3Spec.bindings]
     * on the environment's library classpath and host natives set.
     *
     * The whole swap gates on the *final* [Lwjgl3Spec.enabled] flag —
     * `lwjgl3 { enabled.set(false) }` retracts it: the coord-drop
     * filter reads `enabled` when the library substitutions apply at
     * resolution, and the jar/natives contributions check it lazily.
     */
    fun lwjgl3(action: Action<Lwjgl3Spec>) {
        if (!lwjgl3FilterInstalled) {
            lwjgl3FilterInstalled = true
            libraryCoordFilters += { c ->
                if (lwjgl3.enabled.get() &&
                    c.startsWith("org.lwjgl.lwjgl:")
                ) null else c
            }
        }
        lwjgl3.enabled.set(true)
        action.execute(lwjgl3)
    }

    /** Groovy-DSL counterpart to [lwjgl3]. */
    fun lwjgl3(
        @DelegatesTo(value = Lwjgl3Spec::class, strategy = Closure.DELEGATE_FIRST)
        action: Closure<*>
    ) = lwjgl3(Action<Lwjgl3Spec> { action.delegatingTo(it) })

    /** Additional ordered version-manifest locations searched after
     *  Mojang's official manifest (first match wins; e.g.
     *  `ManifestResolver.SKYRISING_MC_VERSIONS` to restore removed
     *  alpha/beta/classic versions). */
    val manifestUrls = mutableListOf<String>()

    /** Library-overlay URLs merged into the version JSON's `libraries`
     *  (`{name, url?, maxGameVersion?}` arrays; e.g. ornithe-meta's
     *  per-generation backport list). */
    val libraryOverlays = mutableListOf<String>()

    /** Coord prefixes (`group:name`+) of version-JSON libraries that are
     *  obfuscated Mojang artifacts — they route through the env's
     *  raw→dev artifact view so they remap with the MC jar rather than
     *  sitting on the classpath in production namespace (`com.mojang:
     *  realms` is flagged by the provider; this list adds others). */
    val obfuscatedLibraries = mutableListOf<String>()

    /** Declared-source DSL state — populated by [legacydist]. */
    val legacyDist = LegacyDistSpec(project)

    /**
     * Declare this environment's game jar as a user-declared source
     * (file/maven/URL+sha256/producer) — selects
     * `provider("legacydist")` implicitly, or the provider named by
     * `format` when it matches a registered provider (e.g.
     * `format = "devbundle"` for a paperweight dev-bundle zip).
     * Content hints that aren't provider names (`"obf"`) keep the
     * legacydist default.
     */
    fun legacydist(action: org.gradle.api.Action<LegacyDistSpec>) {
        action.execute(legacyDist)
        provider.set(
            legacyDist.format?.takeIf {
                it in project.extensions
                    .getByType(MinecraftExtension::class.java)
                    .registry.registered()
            } ?: "legacydist"
        )
    }

    /** Groovy-DSL counterpart to [legacydist]. */
    fun legacydist(
        @DelegatesTo(value = LegacyDistSpec::class, strategy = Closure.DELEGATE_FIRST)
        action: Closure<*>
    ) {
        action.delegate = legacyDist
        action.resolveStrategy = Closure.DELEGATE_FIRST
        legacydist(Action<LegacyDistSpec> { action.callDelegate() })
    }

    /**
     * Configure this environment against a paperweight dev-bundle zip
     * — `provider("devbundle")` + `format = "devbundle"` preset;
     * declare the bundle with `file()`/`url()`/`producer()` inside
     * (see [DistSpec]). Requires the
     * `xyz.wagyourtail.unimined.provider.devbundle` plugin.
     */
    fun devbundle(action: Action<DistSpec>) {
        provider.set("devbundle")
        legacyDist.format = "devbundle"
        action.execute(DistSpec(this))
    }

    /** Groovy-DSL counterpart to [devbundle]. */
    fun devbundle(@DelegatesTo(value = DistSpec::class, strategy = Closure.DELEGATE_FIRST) action: Closure<*>) =
        devbundle(Action { action.delegate = it; action.resolveStrategy = Closure.DELEGATE_FIRST; action.callDelegate() })

    private fun conversionDist(productId: String, action: Action<DistSpec>) {
        provider.set("conversion")
        product.set("minecraft:$productId")
        action.execute(DistSpec(this))
    }

    /** Configure this environment as a Better Than Adventure dist —
     *  `provider("conversion")` + `product("minecraft:bta")` preset;
     *  `version`/`side`/`loader` remain user-configured. */
    fun bta(action: Action<DistSpec>) = conversionDist("bta", action)

    /** Groovy-DSL counterpart to [bta]. */
    fun bta(@DelegatesTo(value = DistSpec::class, strategy = Closure.DELEGATE_FIRST) action: Closure<*>) =
        conversionDist("bta", Action { action.delegate = it; action.resolveStrategy = Closure.DELEGATE_FIRST; action.callDelegate() })

    /** Configure this environment as a ReIndev dist —
     *  `provider("conversion")` + `product("minecraft:reindev")` preset. */
    fun reindev(action: Action<DistSpec>) = conversionDist("reindev", action)

    /** Groovy-DSL counterpart to [reindev]. */
    fun reindev(@DelegatesTo(value = DistSpec::class, strategy = Closure.DELEGATE_FIRST) action: Closure<*>) =
        conversionDist("reindev", Action { action.delegate = it; action.resolveStrategy = Closure.DELEGATE_FIRST; action.callDelegate() })

    /** Configure this environment as an NSSS dist —
     *  `provider("conversion")` + `product("minecraft:nsss")` preset;
     *  no upstream index, so declare `file()`/`url()` inside. */
    fun nsss(action: Action<DistSpec>) = conversionDist("nsss", action)

    /** Groovy-DSL counterpart to [nsss]. */
    fun nsss(@DelegatesTo(value = DistSpec::class, strategy = Closure.DELEGATE_FIRST) action: Closure<*>) =
        conversionDist("nsss", Action { action.delegate = it; action.resolveStrategy = Closure.DELEGATE_FIRST; action.callDelegate() })

    /**
     * Configure this environment against a user-supplied dist jar —
     * the declared-source seam (provider is NOT forced; it negotiates
     * from the declared source). [name] conventions `product` to
     * `custom:<name>` so loaders declaring `custom`/` * ` compat bind;
     * override `product` inside when the jar is vanilla-compatible
     * (`minecraft:native`) or a known product.
     */
    fun custom(name: String, action: Action<DistSpec>) {
        product.convention("custom:$name")
        action.execute(DistSpec(this))
    }

    /** Groovy-DSL counterpart to [custom]. */
    fun custom(name: String, @DelegatesTo(value = DistSpec::class, strategy = Closure.DELEGATE_FIRST) action: Closure<*>) =
        custom(name, Action { action.delegate = it; action.resolveStrategy = Closure.DELEGATE_FIRST; action.callDelegate() })

    /** Build the provider-facing immutable spec for this environment. */
    fun spec(): ProductSpec {
        validateCombinations()
        return RuntimeProductSpec(
            environmentId = environmentId,
            sourceSetName = environmentId,
            version = RuntimeVersionSpec(
                version.orNull ?: project.failProblem(
                    "env/missing-version",
                    "unimined: environment '$environmentId' has no " +
                        "minecraft version",
                    "set version = <mc version> in the minecraft {} / " +
                        "sourceSet($environmentId) {} block"
                )
            ),
            side = side.get(),
            loader = loaderSpec.orNull,
            product = product.get(),
            mergeIncludeGlobs = mergeIncludeGlobs.getOrElse(emptyList()),
            mergeExcludeGlobs = mergeExcludeGlobs.getOrElse(emptyList()),
            stripBundledServerLibraries = stripBundledServerLibraries.get(),
            sideMarkers = sideMarkers.getOrElse(emptyList()),
            librarySubstitutions = librarySubstitutions.toList(),
            libraryRules = libraryRules.toList(),
            libraryCoordFilters = libraryCoordFilters.toList(),
            manifestUrls = manifestUrls.toList(),
            libraryOverlays = libraryOverlays.toList(),
            obfuscatedLibraries = obfuscatedLibraries.toList(),
            declaredSource = legacyDist.declaration()
        )
    }

    /**
     * Whether tool executions (mcpconfig steps, AT tools, etc.),
     * source-set compiles, and run launchers resolve Gradle java
     * toolchains. Conventions to
     * [MinecraftExtension.useToolchains] (the global kill-switch);
     * 1.x `useToolchains` semantics — opt-out: set false to run on
     * the build JVM (errors when the env requires a newer Java).
     */
    /** Whether this environment's Minecraft is obfuscated — resolved
     *  lazily from the product metadata's mappings channels; 26.x-era
     *  versions ship unobfuscated (no `client_mappings`) so loaders and
     *  mapping defaults must skip obfuscation hops. */
    val obfuscated: Provider<Boolean> = project.provider {
        // whether the shipped jar needs a deobfuscation hop is a
        // product fact — conversion products ship decompiled on any
        // MC version, Mojang jars are obf'd until the 26.x unobf'd
        // era — so the resolved product reports it directly.
        project.extensions.getByType(MinecraftExtension::class.java)
            .resolved(this).obfuscated.get()
    }

    val useToolchains: Property<Boolean> =
        objects.property(Boolean::class.java)
            .convention(project.providers.provider {
                // env instances are constructed while MinecraftExtension
                // itself is being created — resolve lazily
                project.extensions.getByType(MinecraftExtension::class.java)
                    .useToolchains.get()
            })

    /**
     * Java floor for this environment — the transform wiring
     * conventions it from the loader descriptor's `javaVersion`
     * (cleanroom→21, ornithe→17); users may set it directly to force
     * a higher requirement (`minecraft { javaVersion = 21 }`).
     */
    val javaVersion: Property<Int> = objects.property(Int::class.java)

    /**
     * Effective java requirement — the version-json `javaVersion`
     * (provider capabilities) raised by [javaVersion]. Feeds run
     * launchers and tool-exec fallbacks, and is the default compile
     * level via [compileJavaVersion].
     */
    val requiredJavaVersion: Provider<Int> = project.provider {
        maxOf(
            project.extensions.getByType(MinecraftExtension::class.java)
                .artifacts(environmentId).get().capabilities.get().javaVersion,
            javaVersion.getOrElse(0)
        )
    }

    /**
     * The Java level the environment's `compile*` tasks emit at —
     * conventions to [requiredJavaVersion]. Adapters that rewrite
     * class files after `javac` (the jvmdg downgrader) raise it so
     * sources can use newer Java while emitted artifacts are
     * downgraded to the era level; the run JVM floor stays
     * [requiredJavaVersion]. Setting it directly is an escape hatch —
     * nothing post-processes the higher bytecode unless an adapter
     * does.
     */
    val compileJavaVersion: Property<Int> =
        objects.property(Int::class.java).convention(requiredJavaVersion)

    internal fun bindSourceSet(sourceSet: SourceSet) {
        // consumption-time deferral — lifecycle hooks (afterEvaluate,
        // afterProject) are mutation-guarded during plugin application;
        // implicit parent inheritance materializes on first
        // `combinedWith` read (bind fires before the configure block,
        // so `inheritParent` can't be honored here)
        checkVersionConflicts()
        inheritance.boundSourceSet = sourceSet
        inheritance.references.forEach(inheritance::wireSourceOutput)
        wireJavaCompatibility(sourceSet)
        wireEnvJar(sourceSet)
    }

    /** `<env>Jar` — the dev-namespace jar for non-main envs (main
     *  already has `jar`). Registered at bind so task lists stay lazy;
     *  remap<Env>Jar (prod namespace) is registered by the remap module
     *  when the env isn't identity-remap (unobf'd / dev==prod) and
     *  [defaultRemapJar] is set — metadata/JiJ pipeline steps ride
     *  whichever artifact is the publish output. */
    private fun wireEnvJar(sourceSet: SourceSet) {
        if (environmentId == "main") return
        val jarName = sourceSet.getJarTaskName()
        if (project.tasks.findByName(jarName) != null) return
        project.tasks.register(jarName, org.gradle.jvm.tasks.Jar::class.java) { jar ->
            jar.group = "build"
            jar.description = "Assembles the '$environmentId' environment jar"
            jar.archiveAppendix.set(environmentId)
            jar.from(sourceSet.output)
        }
    }

    /**
     * Sets the environment's compile level ([compileJavaVersion]) on
     * its compile tasks: toolchain compiler + `--release` when
     * [useToolchains] is on (release only for levels >= 9 — javac 8
     * rejects the flag); source/target compatibility always (in
     * `doFirst`, where the lazy level resolves) + the build-JVM floor
     * check when toolchains are off (1.x opt-out semantics). The level
     * may exceed [requiredJavaVersion] when an adapter post-processes
     * the class files (jvmdg).
     */
    private fun wireJavaCompatibility(sourceSet: SourceSet) {
        val toolchains = project.extensions.getByType(JavaToolchainService::class.java)
        project.tasks.matching { it.name == sourceSet.getCompileTaskName("java") }
            .configureEach { task ->
            val compile = task as? JavaCompile ?: return@configureEach
            compile.options.release.set(
                compileJavaVersion.map { if (it <= 8) null else it })
            compile.javaCompiler.set(project.providers.provider {
                if (!useToolchains.get()) null
                else toolchains.compilerFor {
                    it.languageVersion.set(compileJavaVersion.map(JavaLanguageVersion::of))
                }.orNull
            })
            // locals only — a `this` capture serializes the whole
            // environment graph (config cache); `objects` rides the
            // service-reference codec so the action can still report
            // through the Problems API — `task.project` can't be
            // touched at execution time under the config cache
            val compileLevel = compileJavaVersion
            val toolchainsOn = useToolchains
            val envId = environmentId
            val objects = project.objects
            compile.doFirst("unimined java compat") {
                val level = compileLevel.get()
                compile.sourceCompatibility = level.toString()
                compile.targetCompatibility = level.toString()
                checkJavaFloor(compile.logger, objects, toolchainsOn, envId, level)
            }
        }
        project.pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
            KotlinWiring.wire(
                project, sourceSet, environmentId,
                compileJavaVersion, useToolchains
            )
        }
    }

    private companion object {

        /** The build-JVM floor check for the toolchains-off path —
         *  compiles run on the daemon JVM, so it must satisfy the
         *  environment's *compile* level ([compileJavaVersion]). */
        private fun checkJavaFloor(
            logger: org.gradle.api.logging.Logger,
            objects: ObjectFactory,
            useToolchains: Provider<Boolean>,
            environmentId: String,
            compileLevel: Int
        ) {
            if (!useToolchains.get() &&
                JavaVersion.current() < JavaVersion.toVersion(compileLevel)) {
                UniminedProblems.fail(
                    logger, objects,
                    "env/java-floor",
                    "unimined: environment '$environmentId' compiles at " +
                        "Java $compileLevel but toolchains are disabled " +
                        "and the build JVM is ${JavaVersion.current()}",
                    "either run Gradle on Java $compileLevel+ or " +
                        "re-enable toolchains (minecraft.useToolchains)")
            }
        }
    }

    /**
     * `jvmTarget` wiring for `compile<Env>Kotlin` — everything KGP
     * lives here so java-only builds never link the kotlin-gradle
     * classes (nested objects load on first reference, which only
     * happens inside the `withPlugin` guard).
     */
    private object KotlinWiring {

        fun wire(
            project: Project,
            sourceSet: SourceSet,
            environmentId: String,
            compileJavaVersion: Provider<Int>,
            useToolchains: Provider<Boolean>
        ) {
            val toolchains = project.extensions
                .getByType(JavaToolchainService::class.java)
            project.tasks.matching {
                it.name == sourceSet.getCompileTaskName("kotlin")
            }.configureEach { task ->
                try {
                    // kotlinc's JDK — same launcher resolution as the
                    // java toolchain; empty provider (toolchains off)
                    // leaves KGP on the build JVM
                    if (task is org.jetbrains.kotlin.gradle.tasks.UsesKotlinJavaToolchain) {
                        task.kotlinJavaToolchain.toolchain.use(
                            useToolchains.flatMap { use ->
                                if (!use) project.provider { null }
                                else toolchains.launcherFor {
                                    it.languageVersion.set(
                                        compileJavaVersion.map(JavaLanguageVersion::of))
                                }
                            }
                        )
                    }
                    @Suppress("UNCHECKED_CAST")
                    val compile = task as? org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask<
                        org.jetbrains.kotlin.gradle.dsl.KotlinJvmCompilerOptions>
                    if (compile == null) {
                        project.reportProblem(
                            "env/kotlin-toolchain",
                            "unimined: '${task.name}' is not a " +
                                "KotlinCompilationTask — jvmTarget not set")
                    } else {
                        compile.compilerOptions.jvmTarget.set(
                            compileJavaVersion.map(::jvmTarget))
                    }
                } catch (e: NoClassDefFoundError) {
                    project.reportProblem(
                        "env/kotlin-toolchain",
                        "unimined: could not wire '${task.name}' — " +
                            "Kotlin Gradle plugin is too old for the " +
                            "compilerOptions API")
                }
                // `objects` is a CC-restorable service reference — the
                // action can't reach `task.project` at execution time
                val objects = project.objects
                task.doFirst("unimined kotlin java floor") {
                    val compileLevel = compileJavaVersion.get()
                    if (!useToolchains.get() &&
                        JavaVersion.current() < JavaVersion.toVersion(compileLevel)) {
                        UniminedProblems.fail(
                            task.logger, objects,
                            "env/java-floor",
                            "unimined: environment '$environmentId' " +
                                "compiles at Java $compileLevel but " +
                                "toolchains are disabled and the build " +
                                "JVM is ${JavaVersion.current()}",
                            "either run Gradle on Java $compileLevel+ or " +
                                "re-enable toolchains " +
                                "(minecraft.useToolchains)")
                    }
                }
            }
        }

        private fun jvmTarget(
            required: Int
        ): org.jetbrains.kotlin.gradle.dsl.JvmTarget = when {
            required <= 8 -> org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8
            else -> try {
                org.jetbrains.kotlin.gradle.dsl.JvmTarget.valueOf("JVM_$required")
            } catch (_: IllegalArgumentException) {
                // newer than this KGP can express — clamp to its max
                org.jetbrains.kotlin.gradle.dsl.JvmTarget.values().last()
            }
        }

    }

    internal fun validateCombinations() {
        project.extensions.getByType(MinecraftExtension::class.java)
            .validateCombinations(this)
    }

    private fun copySections(
        source: EnvironmentReference,
        sections: Set<EnvironmentSection>,
        requireHandlers: Boolean
    ) = inheritance.copySections(source, sections, requireHandlers)

    private fun combineWith(
        source: EnvironmentReference,
        sections: Set<EnvironmentSection>,
        requireHandlers: Boolean
    ) = inheritance.combineWith(source, sections, requireHandlers)

    private fun parseEnvironmentPath(path: String): EnvironmentReference =
        parseEnvironmentPath(project.path, path)

}

/** Managed-object shell whose decorated extension container backs
 *  [MinecraftEnvironment.getExtensions] — Gradle provides the real
 *  container; nothing is ever registered on this type itself. */

