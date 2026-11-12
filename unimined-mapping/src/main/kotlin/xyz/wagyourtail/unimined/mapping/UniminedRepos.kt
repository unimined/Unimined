package xyz.wagyourtail.unimined.mapping

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.artifacts.dsl.RepositoryHandler
import org.gradle.api.initialization.Settings
import org.gradle.api.initialization.resolve.RepositoriesMode
import org.gradle.api.plugins.ExtensionAware
import org.gradle.api.plugins.PluginAware
import java.net.URI
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KProperty

/**
 * The full known-repository catalog — every repo the 1.x
 * `unimined.*Maven`/`mcphackersIvy` helper verbs registered,
 * as data, plus lookups.
 *
 * `UniminedRepos` is a class bound to a [RepositoryHandler]
 * (or `null` for the unbound bootstrap instance): constructing an
 * instance registers its catalog entries into the static maps on
 * first touch and exposes each repo pre-bound —
 *
 * ```
 * UniminedRepos(project).fabric.ensure()           // project repos
 * UniminedRepos(settings.dependencyResolutionManagement.repositories)
 *     .glassLauncher.ensure("babric")              // settings repos
 * ```
 *
 * The companion holds the static API: [ensureForNotation], the
 * deprecated name-keyed [ensure], and the prop-name lookups used
 * by the bound accessors.
 */
class UniminedRepos private constructor(
    private val handler: RepositoryHandler?
) {

    private constructor(target: PluginAware) : this(handlerFor(target))

    /** A catalog repo — registerable on a [RepositoryHandler]. */
    sealed interface Repo {
        /** Gradle-visible repo name — the catalog key and dedupe
         *  key for [UniminedRepos.ensure]. */
        val name: String

        /** Maven groups this repo is known to serve — consulted by
         *  [ensureForNotation] to pick a repo from a notation's
         *  group. */
        val servedGroups: Set<String> get() = emptySet()

        fun registerOn(repos: RepositoryHandler)

        /** Register this repo on [repos] when absent — for any
         *  [RepositoryHandler]: project repositories, settings
         *  `dependencyResolutionManagement`/`pluginManagement`, etc. */
        fun ensure(repos: RepositoryHandler) {
            if (repos.none { it.name == name }) registerOn(repos)
        }

        /** Register this repo on [project] when absent — honors the
         *  build's `repositoriesMode` (see [UniminedRepos.ensure]). */
        fun ensure(project: Project) = UniminedRepos.ensure(this, project)

        /** Register this repo on [settings]'s
         *  `dependencyResolutionManagement.repositories` when absent —
         *  usable from settings.gradle(.kts) once the settings plugin
         *  puts this catalog on the script classpath. */
        fun ensure(settings: Settings) =
            ensure(settings.dependencyResolutionManagement.repositories)
    }

    /** Maven repo with optional artifact-only metadata fallback —
     *  pre-maven-era artifacts often ship no POM. */
    data class Maven(
        override val name: String,
        val url: String,
        val artifactFallback: Boolean = false,
        val includeGroups: Set<String> = emptySet(),
        val includeGroupRegex: String? = null,
        val excludeGroups: Set<String> = emptySet(),
        override val servedGroups: Set<String> = emptySet()
    ) : Repo {
        override fun registerOn(repos: RepositoryHandler) {
            repos.maven { r ->
                r.name = this@Maven.name
                r.setUrl(url)
                if (includeGroups.isNotEmpty() || includeGroupRegex != null ||
                    excludeGroups.isNotEmpty()) {
                    r.content { c ->
                        includeGroups.forEach(c::includeGroup)
                        includeGroupRegex?.let(c::includeGroupByRegex)
                        excludeGroups.forEach(c::excludeGroup)
                    }
                }
                if (artifactFallback) {
                    r.metadataSources { s ->
                        s.mavenPom()
                        s.artifact()
                    }
                }
            }
        }
    }

    /** Ivy repo with a custom artifact pattern — for feeds that
     *  aren't maven-shaped at all (mcphackers `versionsV2`,
     *  GitHub release download pages). */
    data class Ivy(
        override val name: String,
        val url: String,
        val artifactPattern: String,
        val includeModules: Set<Pair<String, String>> = emptySet(),
        override val servedGroups: Set<String> = emptySet()
    ) : Repo {
        override fun registerOn(repos: RepositoryHandler) {
            repos.ivy { r ->
                r.name = this@Ivy.name
                r.url = URI.create(url)
                r.patternLayout { p -> p.artifact(artifactPattern) }
                if (includeModules.isNotEmpty()) {
                    r.content { c ->
                        includeModules.forEach { (g, m) ->
                            c.includeModule(g, m)
                        }
                    }
                }
                r.metadataSources { s -> s.artifact() }
            }
        }
    }

    /** A parameterized catalog entry — a [Repo] factory keyed on a
     *  suffix (the 1.x `*Maven("snapshots")` verb form). */
    sealed interface ParameterizedRepo {
        /** Maven group → the suffix that resolves it, consulted by
         *  [ensureForNotation]. */
        val servedGroups: Map<String, String> get() = emptyMap()

        fun resolve(suffix: String): Repo

        /** Resolve to the [suffix] repo and register it on [repos]
         *  when absent. */
        fun ensure(suffix: String, repos: RepositoryHandler) =
            resolve(suffix).ensure(repos)

        /** Resolve to the [suffix] repo and register it on [project]
         *  when absent — `glassLauncher.ensure("snapshots", project)`. */
        fun ensure(suffix: String, project: Project) =
            resolve(suffix).ensure(project)

        /** Resolve to the [suffix] repo and register it on [settings]'s
         *  `dependencyResolutionManagement.repositories` when absent. */
        fun ensure(suffix: String, settings: Settings) =
            resolve(suffix).ensure(settings)
    }

    /** A 1.x parameterized maven — the suffix fills `{name}` slots in
     *  [namePattern]/[urlPattern] (`glassLauncherMaven("snapshots")` →
     *  `maven.glass-launcher.net/snapshots/`). */
    data class ParameterizedMaven(
        val namePattern: String,
        val urlPattern: String,
        val artifactFallback: Boolean = false,
        val includeGroups: Set<String> = emptySet(),
        val includeGroupRegex: String? = null,
        override val servedGroups: Map<String, String> = emptyMap()
    ) : ParameterizedRepo {
        override fun resolve(suffix: String) = Maven(
            namePattern.replace("{name}", suffix),
            urlPattern.replace("{name}", suffix),
            artifactFallback,
            // scope the repo to the groups it declares for this
            // suffix — a mirror/proxy maven (e.g. wagyourtail
            // mirroring loader repos) otherwise resolves a module's
            // metadata and then fails artifact fetch with no
            // fallback, shadowing the real upstream maven
            includeGroups + servedGroups.filterValues { it == suffix }.keys,
            includeGroupRegex
        )
    }

    /** A parameterized ivy feed — the suffix fills `{name}` slots in
     *  [namePattern]/[urlPattern]. For feeds that aren't maven-shaped
     *  at all, e.g. GitHub release download pages
     *  (`github.com/{owner}/{repo}/releases/download/{tag}/{file}`). */
    data class ParameterizedIvy(
        val namePattern: String,
        val urlPattern: String,
        val artifactPattern: String,
        val includeModules: Set<Pair<String, String>> = emptySet(),
        override val servedGroups: Map<String, String> = emptyMap()
    ) : ParameterizedRepo {
        override fun resolve(suffix: String) = Ivy(
            namePattern.replace("{name}", suffix),
            urlPattern.replace("{name}", suffix),
            artifactPattern, includeModules
        )
    }

    /** A catalog [Repo] pre-bound to this instance's handler —
     *  `ensure()` needs no argument; all other members delegate to
     *  the catalog entry. */
    class BoundRepo(
        val delegate: Repo,
        private val handler: RepositoryHandler?
    ) : Repo by delegate {
        /** Register on the bound handler — errors on an unbound
         *  (handler = null) instance. */
        fun ensure() = handler?.let { ensure(it) }
            ?: error("unbound UniminedRepos instance — " +
                "construct with a RepositoryHandler")
    }

    /** A catalog [ParameterizedRepo] pre-bound to this instance's
     *  handler — `ensure(suffix)` needs only the 1.x verb's suffix. */
    class BoundParameterizedRepo(
        delegate: ParameterizedRepo,
        private val handler: RepositoryHandler?
    ) : ParameterizedRepo by delegate {
        /** Resolve to [suffix] and register on the bound handler. */
        fun ensure(suffix: String) = handler?.let { ensure(suffix, it) }
            ?: error("unbound UniminedRepos instance — " +
                "construct with a RepositoryHandler")
    }

    // instance maps — the catalog each instance registers on
    // construction (repos are scoped per target anyway)
    private val repos: MutableMap<String, Repo> = mutableMapOf()
    private val byProp: MutableMap<String, Repo> = mutableMapOf()
    private val parameterized: MutableMap<String, ParameterizedRepo> =
        mutableMapOf()

    // self-registering delegates: `val x by Maven(...)` records the
    // repo in the maps (under its display name AND the property
    // name) on construction, and yields the handler-bound entry
    operator fun <T : Repo> T.provideDelegate(
        thisRef: UniminedRepos, property: KProperty<*>
    ): ReadOnlyProperty<Any?, BoundRepo> {
        repos.putIfAbsent(name, this)
        byProp.putIfAbsent(property.name, this)
        val entry = this
        return ReadOnlyProperty { _, _ ->
            BoundRepo(entry, thisRef.handler)
        }
    }

    operator fun <T : ParameterizedRepo> T.provideDelegate(
        thisRef: UniminedRepos, property: KProperty<*>
    ): ReadOnlyProperty<Any?, BoundParameterizedRepo> {
        parameterized.putIfAbsent(property.name, this)
        val entry = this
        return ReadOnlyProperty { _, _ ->
            BoundParameterizedRepo(entry, thisRef.handler)
        }
    }

    /** Maven Central — named `MavenRepo` so it dedupes against
     *  Gradle's own `mavenCentral()` (settings-mode checks are
     *  name-keyed). The excludes mirror 1.x: `com.mojang` artifacts
     *  live authoritatively on Mojang's host, and `org.lwjgl` must
     *  resolve there too — 1.21's `natives-macos-patch` classifier is
     *  absent from central, and Gradle pins a module's artifacts to
     *  whichever repo served its metadata. Registering central FIRST
     *  keeps its classifiers reachable for modules both hosts carry
     *  (netty natives — libraries.minecraft.net serves the POM + main
     *  jar but not the `linux-x86_64` classifier). */
    val mavenCentral by Maven("MavenRepo",
        "https://repo.maven.apache.org/maven2/",
        excludeGroups = setOf("com.mojang", "org.lwjgl"))
    val mojangLibraries by Maven("Mojang Libraries",
        "https://libraries.minecraft.net",
        excludeGroups = setOf("ca.weblite"))
    val minecraftForge by Maven("Minecraft Forge",
        "https://maven.minecraftforge.net/", artifactFallback = true,
        servedGroups = setOf("de.oceanlabs.mcp", "net.minecraftforge"))
    val neoForged by Maven("NeoForged",
        "https://maven.neoforged.net/releases",
        servedGroups = setOf("net.neoforged"))
    val fabric by Maven("Fabric", "https://maven.fabricmc.net",
        servedGroups = setOf("net.fabricmc"))
    val ornithe by Maven("Ornithe",
        "https://maven.ornithemc.net/releases",
        servedGroups = setOf("net.ornithemc"))
    val legacyFabric by Maven("Legacy Fabric",
        "https://maven.legacyfabric.net",
        servedGroups = setOf("net.legacyfabric"))
    val quilt by Maven("Quilt",
        "https://maven.quiltmc.org/repository/release",
        servedGroups = setOf("org.quiltmc"))
    val parchment by Maven("Parchment", "https://maven.parchmentmc.org/",
        servedGroups = setOf("org.parchmentmc"))
    val sonatypeStaging by Maven("Sonatype Staging",
        "https://oss.sonatype.org/content/repositories/staging/")
    val sponge by Maven("Sponge", "https://repo.spongepowered.org/maven/",
        servedGroups = setOf("org.spongepowered"))
    val jitpack by Maven("Jitpack", "https://jitpack.io",
        servedGroups = setOf("com.github"))
    val spigot by Maven("Spigot",
        "https://hub.spigotmc.org/nexus/content/repositories/snapshots/",
        servedGroups = setOf("org.spigotmc", "org.bukkit"))
    val sleepingTown by Maven("Sleeping Town",
        "https://repo.sleeping.town",
        servedGroups = setOf("com.unascribed"))
    val arcseekers by Maven("Arcseekers",
        "https://maven.arcseekers.com/releases",
        servedGroups = setOf("com.cleanroommc"))
    val fox2Code by Maven("Fox2Code", "https://cdn.fox2code.com/maven",
        servedGroups = setOf("com.fox2code", "com.fox2code.FoxLoader"))
    val modrinth by Maven("Modrinth", "https://api.modrinth.com/maven",
        servedGroups = setOf("maven.modrinth"))
    val curse by Maven("Curse", "https://cursemaven.com",
        servedGroups = setOf("curse.maven"))
    val betaCurse by Maven("Beta Curse", "https://beta.cursemaven.com")
    val liteloader by Maven("Liteloader",
        "https://dl.liteloader.com/versions/",
        servedGroups = setOf("com.mumfrey"))
    val paper by Maven("Paper",
        "https://repo.papermc.io/repository/maven-public/")
    // JST (sources-surface enrichers on userdev-era forge) depends on
    // IntelliJ platform modules and `-jb`-suffixed republished deps —
    // both JetBrains cache-redirector mavens get the same group filter
    val jetbrainsIntellijRepository by Maven(
        "JetBrains Intellij Repository",
        "https://cache-redirector.jetbrains.com/intellij-repository",
        includeGroupRegex = JETBRAINS_GROUPS)
    val jetbrainsIntellijDependencies by Maven(
        "JetBrains Intellij Dependencies",
        "https://cache-redirector.jetbrains.com/intellij-dependencies",
        includeGroupRegex = JETBRAINS_GROUPS)
    val mcphackers by Ivy("mcphackers",
        "https://mcphackers.github.io/versionsV2/",
        "[revision].[ext]",
        setOf("io.github.mcphackers" to "mcp"),
        servedGroups = setOf("io.github.mcphackers"))

    /** 1.x parameterized repos — the property name is the 1.x verb; the
     *  suffix fills `{name}` slots in the repo's name/url. */
    val glassLauncher by ParameterizedMaven(
        "Glass Launcher ({name})",
        "https://maven.glass-launcher.net/{name}/",
        servedGroups = mapOf(
            "babric" to "babric",
            "net.glasslauncher" to "releases"
        ))
    val wispForest by ParameterizedMaven(
        "Wisp Forest ({name})", "https://maven.wispforest.io/{name}",
        servedGroups = mapOf("me.alphamode" to "releases"))
    val wagYourTail by ParameterizedMaven(
        "WagYourTail ({name})",
        "https://maven.wagyourtail.xyz/{name}/",
        artifactFallback = true,
        servedGroups = mapOf("xyz.wagyourtail" to "releases"))
    // `flint` preset removed — maven.flintloader.net is offline; the
    // loader descriptor was dropped with it.
    val cleanroom by ParameterizedMaven(
        "cleanroom-{name}", "https://repo.cleanroommc.com/{name}")
    val signalum by ParameterizedMaven(
        "Signalum ({name})",
        "https://maven.thesignalumproject.net/{name}/",
        artifactFallback = true)
    /** GitHub release downloads as an ivy feed — `github("wagyourtail")`
     *  resolves `github.com/wagyourtail/<repo>/releases/download/
     *  <tag>/<artifact>-<tag>.<ext>`; assumes the conventional
     *  `artifact-version.ext` attachment naming. */
    val github by ParameterizedIvy(
        "GitHub ({name})",
        "https://github.com/{name}",
        "[module]/releases/download/[revision]/[artifact]-[revision].[ext]")

    /** Look up a catalog repo by display name, or `"verb:suffix"`
     *  for parameterized entries. */
    fun repoFor(name: String): Repo {
        val verb = name.substringBefore(':')
        return if (':' in name) {
            parameterizedForProp(verb).resolve(
                name.substringAfter(':'))
        } else {
            repos[name] ?: error("unknown unimined repo '$name'")
        }
    }

    /** Look up a plain catalog repo by its delegated property
     *  name — e.g. `"fabric"` → the Fabric maven. */
    fun repoForProp(prop: String): Repo =
        byProp[prop] ?: error("unknown unimined repo property '$prop'")

    /** Look up a parameterized factory by its delegated property
     *  name — the 1.x verb, e.g. `"glassLauncher"`. */
    fun parameterizedForProp(prop: String): ParameterizedRepo =
        parameterized[prop]
            ?: error("unknown parameterized unimined repo '$prop'")

    /** Register the repo named [name] when not already present —
     *  parameterized entries use `"verb:suffix"`. */
    @Deprecated(
        "use the typed catalog accessor — " +
            "UniminedRepos(handler).<repo>.ensure(); parameterized " +
            "repos go through ensureParameterized(verb, suffix, " +
            "project)"
    )
    fun ensure(name: String, project: Project) =
        ensure(repoFor(name), project)

    /** The 1.x parameterized form —
     *  `ensureParameterized("glassLauncher", "snapshots", project)`
     *  registers `maven.glass-launcher.net/snapshots/`. */
    @Deprecated(
        "use the typed catalog accessor — " +
            "UniminedRepos(handler).<verb>.ensure(suffix)"
    )
    fun ensureParameterized(
        verb: String, suffix: String, project: Project
    ) = parameterizedForProp(verb).resolve(suffix).ensure(project)

    /**
     * Register the repo a maven [notation] resolves through, keyed
     * on its group — e.g. `net.ornithemc:feather:...` registers the
     * Ornithe maven. No-op for groups outside the catalog (those
     * resolve through the project's own declared repos).
     */
    fun ensureForNotation(notation: String, project: Project) {
        val group = notation.substringBefore(':')
        for (repo in repos.values) {
            if (group in repo.servedGroups) {
                return repo.ensure(project)
            }
        }
        for (p in parameterized.values) {
            p.servedGroups[group]?.let {
                return p.ensure(it, project)
            }
        }
    }

    companion object {

        private const val JETBRAINS_GROUPS =
            "com\\.jetbrains\\..*|com\\.intellij\\..*|org\\.jetbrains\\..*|com\\.github\\.weisj"

        private val instances = java.util.WeakHashMap<PluginAware, UniminedRepos>()

        /** The bound catalog for [target] — reuses the `uniminedRepos`
         *  extension when present (the mapping plugin registers one);
         *  otherwise creates, registers it, and returns it. */
        @Synchronized
        operator fun invoke(target: PluginAware): UniminedRepos {
            val container = (target as? ExtensionAware)?.extensions
                ?: return instances.getOrPut(target) {
                    UniminedRepos(target)
                }
            container.findByName("uniminedRepos")
                ?.let { return it as UniminedRepos }
            return UniminedRepos(target)
                .also { container.add("uniminedRepos", it) }
        }

        /** A catalog bound to an arbitrary [RepositoryHandler]. */
        operator fun invoke(handler: RepositoryHandler?) = UniminedRepos(handler)

        private fun handlerFor(target: PluginAware): RepositoryHandler? =
            when (target) {
                is Project -> target.repositories
                is Settings -> target.dependencyResolutionManagement.repositories
                else -> null
            }

        /** Register [repo] on [project] when absent. Honors the build's
         *  `dependencyResolutionManagement.repositoriesMode`: a matching
         *  settings-level repo satisfies the requirement;
         *  `FAIL_ON_PROJECT_REPOS` without one throws with the block to
         *  paste into settings; `PREFER_SETTINGS` warns and skips. */
        fun ensure(repo: Repo, project: Project) {
            if (!applyRepositoriesMode(project, repo, repo.name)) return
            try {
                repo.ensure(project.repositories)
            } catch (e: Exception) {
                if (isProjectRepoBlocked(e)) {
                    throw GradleException(
                        settingsBlockMessage(repo, repo.name))
                }
                throw e
            }
        }

        /**
         * Check `dependencyResolutionManagement.repositoriesMode` —
         * returns false when a project-level register must be skipped
         * (already declared in settings, or ignored under
         * `PREFER_SETTINGS`). `FAIL_ON_PROJECT_REPOS` throws eagerly
         * with the settings block to add.
         */
        private fun applyRepositoriesMode(
            project: Project, repo: Repo, repoName: String
        ): Boolean {
            val management = try {
                (project.gradle as? org.gradle.api.internal.GradleInternal)
                    ?.settings?.dependencyResolutionManagement
            } catch (e: Exception) {
                null
            } ?: return true
            val declaredInSettings = try {
                management.repositories.any { it.name == repoName }
            } catch (e: Exception) {
                false
            }
            when (management.repositoriesMode.get()) {
                RepositoriesMode.FAIL_ON_PROJECT_REPOS -> {
                    if (declaredInSettings) return false
                    throw GradleException(
                        settingsBlockMessage(repo, repoName))
                }
                RepositoriesMode.PREFER_SETTINGS -> {
                    if (!declaredInSettings) {
                        project.logger.warn(
                            "[Unimined] repository '$repoName' is not " +
                                "in settings repositories and " +
                                "project-level repos are ignored " +
                                "under PREFER_SETTINGS — artifacts " +
                                "may fail to resolve"
                        )
                    }
                    return false
                }
                else -> return true
            }
        }

        /** The registration-time fallback for the settings-mode
         *  check: Gradle itself rejects the project repo add under
         *  `FAIL_ON_PROJECT_REPOS`. */
        private fun isProjectRepoBlocked(e: Throwable): Boolean {
            val messages = generateSequence(e as Throwable?) { it.cause }
                .mapNotNull { it.message }.joinToString(" ")
            return "RepositoriesMode" in messages ||
                "settings repositories" in messages ||
                "project repositories" in messages
        }

        private fun settingsBlockMessage(repo: Repo, repoName: String) =
            "unimined needs repository '$repoName' but this build " +
                "enforces dependencyResolutionManagement." +
                "repositoriesMode = FAIL_ON_PROJECT_REPOS. Declare " +
                "it in settings.gradle.kts:\n" +
                "  dependencyResolutionManagement {\n" +
                "    repositories {\n        " +
                repoSnippet(repo).replace("\n", "\n        ") +
                "\n    }\n  }"

        private fun repoSnippet(repo: Repo): String = when (repo) {
            is Maven -> buildString {
                append("""maven("${repo.url}") {
                |name = "${repo.name}""""".trimMargin())
                val filters = buildList {
                    repo.includeGroups.forEach {
                        add("includeGroup(\"$it\")")
                    }
                    repo.includeGroupRegex?.let {
                        add("includeGroupByRegex(\"$it\")")
                    }
                    repo.excludeGroups.forEach {
                        add("excludeGroup(\"$it\")")
                    }
                }
                if (filters.isNotEmpty()) {
                    append("\ncontent {\n    ")
                    append(filters.joinToString("\n    "))
                    append("\n}")
                }
                append("\n}")
            }
            is Ivy -> """ivy("${repo.url}") {
                patternLayout { artifact("${repo.artifactPattern}") }
            }"""
            is BoundRepo -> repoSnippet(repo.delegate)
        }
    }
}

/** A project-repositories-bound catalog view —
 *  `project.uniminedRepos().fabric.ensure()`. */
fun Project.uniminedRepos() = UniminedRepos(this)
