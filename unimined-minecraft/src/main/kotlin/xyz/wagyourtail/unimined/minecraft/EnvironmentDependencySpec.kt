package xyz.wagyourtail.unimined.minecraft

import org.gradle.api.Project
import org.gradle.api.artifacts.Dependency

/**
 * The receiver of an environment's `dependencies { }` block — a scoped
 * syntactic lift over the environment's *existing* dependency buckets
 * (KMP source-set-dependencies style). Every verb resolves the
 * configuration the env's source set already owns and adds the notation
 * to it; no new resolution machinery exists behind the block, and the
 * string configuration names remain fully functional alongside it.
 *
 * Vocabulary:
 *
 *  - `implementation`/`compileOnly`/`runtimeOnly` — the source set's
 *    ordinary java buckets (`implementation` for `main`,
 *    `<env>Implementation` for named envs). Non-mod jars on these pass
 *    through un-remapped, like the global `dependencies { }` block.
 *  - `api` — materialized on first use where Gradle doesn't create it:
 *    `api` exists only for `main` under `java-library`, and custom
 *    source sets have no `<env>Api` bucket at all. First use creates
 *    the bucket and wires `<env>Implementation.extendsFrom(<env>Api)` —
 *    java-library-equivalent semantics (compile+runtime visible
 *    in-dev), which unimined's publish wiring then carries in the
 *    env's api-elements channel like any existing `<env>Api`.
 *  - `modImplementation`/`modCompileOnly`/`modRuntimeOnly` — the env's
 *    mod buckets: mod jars remapped from the mod source namespace into
 *    the dev namespace. `modCompileOnly` is compile-classpath-only —
 *    never on runtime classpaths, never published. `modApi` is
 *    deliberately absent: publication semantics for compile-visible
 *    mod deps are undecided (future work — loom's `modApi` carries
 *    dependents-visible access rules, which unimined's access-model
 *    channel would need to express first).
 *  - `include` — the jar-in-jar embed bucket. NOT a classpath
 *    dependency: entries ship inside the production jar but are never
 *    compiled or launched against — declare `modImplementation` (or
 *    `implementation` for library jarjar deps) alongside when dev runs
 *    need the contents.
 *
 * Dependency notation passes straight through to the project's
 * [org.gradle.api.artifacts.dsl.DependencyHandler], so every shape the
 * global block accepts (coordinates, maps, `project(...)`, `files(...)`,
 * providers) works here. Exotic per-dependency configuration
 * (`{ transitive = false }`, capability tweaks) has no scoped twin —
 * declare it on the string configuration name instead.
 */
class EnvironmentDependencySpec internal constructor(
    private val environment: MinecraftEnvironment,
    private val project: Project
) {

    private val minecraft: MinecraftExtension by lazy {
        project.extensions.getByType(MinecraftExtension::class.java)
    }

    /** `<env>Implementation`-style bucket name — `implementation` for
     *  `main`, `<envId><Suffix>` for named envs (mirrors the java
     *  plugin's source-set naming, and the copy handler's). */
    private fun javaBucketName(suffix: String): String =
        if (environment.environmentId == "main") {
            suffix.replaceFirstChar(Char::lowercaseChar)
        } else {
            "${environment.environmentId}$suffix"
        }

    /**
     * Add [notation] to the source-set-owned java bucket [name]. The
     * java plugin creates these buckets when the env's source set
     * materializes, which binding normally does before the env's
     * configure block runs — but an imperative `apply(plugin = "java")`
     * *after* the env block leaves them missing at call time. Rather
     * than fail or silently drop, defer through a live
     * `matching().all` hook — the same lazy-safe idiom the copy
     * handler uses — so the declaration lands whenever the bucket
     * appears.
     */
    private fun addToJavaBucket(name: String, notation: Any): Dependency? {
        if (project.configurations.findByName(name) != null) {
            return project.dependencies.add(name, notation)
        }
        project.configurations.matching { it.name == name }.all {
            project.dependencies.add(name, notation)
        }
        return null
    }

    /**
     * Add [notation] to one of the env's unimined-owned buckets — these
     * are created on demand via
     * [MinecraftExtension.ensureModConfigurations], so they exist
     * regardless of java-plugin ordering.
     */
    private fun addToModBucket(name: String, notation: Any): Dependency? {
        minecraft.ensureModConfigurations(environment)
        return project.dependencies.add(name, notation)
    }

    /** Compile+runtime dependency of this environment's source set —
     *  `implementation` / `<env>Implementation`. */
    fun implementation(notation: Any): Dependency? =
        addToJavaBucket(javaBucketName("Implementation"), notation)

    /** Compile-only dependency of this environment's source set —
     *  `compileOnly` / `<env>CompileOnly`. */
    fun compileOnly(notation: Any): Dependency? =
        addToJavaBucket(javaBucketName("CompileOnly"), notation)

    /** Runtime-only dependency of this environment's source set —
     *  `runtimeOnly` / `<env>RuntimeOnly`. */
    fun runtimeOnly(notation: Any): Dependency? =
        addToJavaBucket(javaBucketName("RuntimeOnly"), notation)

    /**
     * Api dependency of this environment's source set — `api` /
     * `<env>Api`, materialized on first use. Gradle creates `api` only
     * for `main` under the `java-library` plugin and never creates
     * `<env>Api` for custom source sets (there is no java consumable
     * variant for it); unimined creates the missing bucket and wires
     * `<env>Implementation.extendsFrom(<env>Api)`, giving it
     * java-library-equivalent in-dev semantics. Pre-creating the bucket
     * is safe against a later `java-library` application (Gradle's own
     * creation is tolerant), and unimined's dev-variant publishing
     * already extends its api-elements channel from an existing
     * `<env>Api`, so the dependency surface stays meaningful for
     * consumers of the env's artifact.
     */
    fun api(notation: Any): Dependency? {
        val apiName = javaBucketName("Api")
        val api = project.configurations.maybeCreate(apiName).apply {
            isCanBeConsumed = false
            isCanBeResolved = false
        }
        // live hook like addToJavaBucket — `implementation` can lag the
        // env block when java is applied imperatively after the DSL
        val implName = javaBucketName("Implementation")
        project.configurations.matching { it.name == implName }.all { impl ->
            if (api !in impl.extendsFrom) {
                impl.extendsFrom(api)
            }
        }
        return project.dependencies.add(apiName, notation)
    }

    /** Mod jar to compile and run against in this environment —
     *  remapped from the mod source namespace into the dev namespace.
     *  (`modImplementation` / `<env>ModImplementation`.) */
    fun modImplementation(notation: Any): Dependency? =
        addToModBucket(environment.modImplementationConfigurationName, notation)

    /** Mod jar needed only at compile time in this environment —
     *  remapped like [modImplementation] but never on runtime
     *  classpaths and never published.
     *  (`modCompileOnly` / `<env>ModCompileOnly`.) */
    fun modCompileOnly(notation: Any): Dependency? =
        addToModBucket(environment.modCompileOnlyConfigurationName, notation)

    /** Mod jar needed only at runtime in this environment —
     *  (`modRuntimeOnly` / `<env>ModRuntimeOnly`.) */
    fun modRuntimeOnly(notation: Any): Dependency? =
        addToModBucket(environment.modRuntimeOnlyConfigurationName, notation)

    /**
     * Jar embedded into this environment's production artifact
     * (jar-in-jar — `include` / `<env>Include`). NOT a classpath
     * dependency: entries ship inside the production jar but are never
     * compiled or launched against — declare [modImplementation] (or
     * [implementation] for library jarjar deps) alongside when dev runs
     * need the contents.
     */
    fun include(notation: Any): Dependency? =
        addToModBucket(environment.includeConfigurationName, notation)

}
