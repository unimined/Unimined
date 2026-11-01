package xyz.wagyourtail.unimined.mapping

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.artifacts.dsl.RepositoryHandler
import org.gradle.api.initialization.resolve.RepositoriesMode
import java.net.URI

/**
 * The full known-repository catalog — every repo the 1.x
 * `unimined.*Maven`/`mcphackersIvy` helper verbs registered,
 * as data, plus lookups:
 *
 * - [ensure] — register a named repo when absent (channel wiring
 *   that already knows which repo it needs)
 * - [ensureForNotation] — derive the repo from a maven notation's
 *   group (`io.github.mcphackers:mcp:b1.7@zip` → the mcphackers
 *   ivy) — used by the shared maven resolvers so spec `maven()`
 *   sources resolve without the project declaring the repo.
 *
 * Parameterized repos (glassLauncher, wagYourTail, wispForest,
 * flint, cleanroom, signalum) take the 1.x repo name suffix via
 * [parameterized] — `parameterized("glassLauncher", "snapshots")`.
 */
object UniminedRepos {

    /** Maven repo with optional artifact-only metadata fallback —
     *  pre-maven-era artifacts often ship no POM. */
    data class Maven(
        val name: String,
        val url: String,
        val artifactFallback: Boolean = false,
        val includeGroups: Set<String> = emptySet()
    ) {
        fun registerOn(repos: RepositoryHandler) {
            repos.maven { r ->
                r.name = this@Maven.name
                r.setUrl(url)
                if (includeGroups.isNotEmpty()) {
                    r.content { c -> includeGroups.forEach(c::includeGroup) }
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
     *  aren't maven-shaped at all (mcphackers `versionsV2`). */
    data class Ivy(
        val name: String,
        val url: String,
        val artifactPattern: String,
        val includeModules: Set<Pair<String, String>> = emptySet()
    ) {
        fun registerOn(repos: RepositoryHandler) {
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

    private val repos: Map<String, Any> = mapOf(
        "Minecraft Forge" to Maven("Minecraft Forge",
            "https://maven.minecraftforge.net/", artifactFallback = true),
        "NeoForged" to Maven("NeoForged",
            "https://maven.neoforged.net/releases"),
        "Fabric" to Maven("Fabric", "https://maven.fabricmc.net"),
        "Ornithe" to Maven("Ornithe",
            "https://maven.ornithemc.net/releases"),
        "Legacy Fabric" to Maven("Legacy Fabric",
            "https://maven.legacyfabric.net"),
        "Quilt" to Maven("Quilt",
            "https://maven.quiltmc.org/repository/release"),
        "Parchment" to Maven("Parchment", "https://maven.parchmentmc.org/"),
        "Sonatype Staging" to Maven("Sonatype Staging",
            "https://oss.sonatype.org/content/repositories/staging/"),
        "Sponge" to Maven("Sponge", "https://repo.spongepowered.org/maven/"),
        "Jitpack" to Maven("Jitpack", "https://jitpack.io"),
        "Spigot" to Maven("Spigot",
            "https://hub.spigotmc.org/nexus/content/repositories/snapshots/"),
        "Sleeping Town" to Maven("Sleeping Town",
            "https://repo.sleeping.town"),
        "Arcseekers" to Maven("Arcseekers",
            "https://maven.arcseekers.com/releases"),
        "Fox2Code" to Maven("Fox2Code", "https://cdn.fox2code.com/maven"),
        "Modrinth" to Maven("Modrinth", "https://api.modrinth.com/maven"),
        "Curse" to Maven("Curse", "https://cursemaven.com"),
        "Beta Curse" to Maven("Beta Curse", "https://beta.cursemaven.com"),
        "Liteloader" to Maven("Liteloader",
            "https://dl.liteloader.com/versions/"),
        "Paper" to Maven("Paper",
            "https://repo.papermc.io/repository/maven-public/"),
        "mcphackers" to Ivy("mcphackers",
            "https://mcphackers.github.io/versionsV2/",
            "[revision].[ext]",
            setOf("io.github.mcphackers" to "mcp")),
    )

    /** 1.x parameterized repos — keyed by the 1.x verb name; the
     *  suffix fills the repo's name-slot (`glassLauncherMaven("snapshots")`
     *  → `maven.glass-launcher.net/snapshots/`). */
    private val parameterized: Map<String, (String) -> Maven> = mapOf(
        "glassLauncher" to { name ->
            Maven("Glass Launcher ($name)",
                "https://maven.glass-launcher.net/$name/")
        },
        "wispForest" to { name ->
            Maven("Wisp Forest ($name)", "https://maven.wispforest.io/$name")
        },
        "wagYourTail" to { name ->
            Maven("WagYourTail ($name)",
                "https://maven.wagyourtail.xyz/$name/",
                artifactFallback = true)
        },
        "flint" to { name ->
            Maven("Flint ($name)", "https://maven.flintloader.net/$name/")
        },
        "cleanroom" to { name ->
            Maven("cleanroom-$name", "https://repo.cleanroommc.com/$name")
        },
        "signalum" to { name ->
            Maven("Signalum ($name)",
                "https://maven.thesignalumproject.net/$name/",
                artifactFallback = true)
        },
    )

    /** Maven group -> catalog repo name, for [ensureForNotation]. */
    private val groupToRepo: Map<String, String> = mapOf(
        "io.github.mcphackers" to "mcphackers",
        "net.ornithemc" to "Ornithe",
        "net.fabricmc" to "Fabric",
        "net.legacyfabric" to "Legacy Fabric",
        "org.quiltmc" to "Quilt",
        "org.parchmentmc" to "Parchment",
        "de.oceanlabs.mcp" to "Minecraft Forge",
        "net.minecraftforge" to "Minecraft Forge",
        "net.neoforged" to "NeoForged",
        "org.spongepowered" to "Sponge",
        "org.spigotmc" to "Spigot",
        "org.bukkit" to "Spigot",
        "com.github" to "Jitpack",
        "maven.modrinth" to "Modrinth",
        "curse.maven" to "Curse",
        "com.mumfrey" to "Liteloader",
        "com.unascribed" to "Sleeping Town",
        "com.fox2code" to "Fox2Code",
        "com.fox2code.FoxLoader" to "Fox2Code",
        "com.cleanroommc" to "Arcseekers",
        "me.alphamode" to "wispForest:releases",
        "xyz.wagyourtail" to "wagYourTail:releases",
        "babric" to "glassLauncher:babric",
        "net.glasslauncher" to "glassLauncher:releases",
        "net.flintloader" to "flint:releases",
    )

    /** Register the repo named [name] when not already present —
     *  parameterized entries use `"verb:suffix"` (e.g.
     *  `"glassLauncher:snapshots"`). Honors the build's
     *  `dependencyResolutionManagement.repositoriesMode`: a matching
     *  settings-level repo satisfies the requirement;
     *  `FAIL_ON_PROJECT_REPOS` without one throws with the block to
     *  paste into settings; `PREFER_SETTINGS` warns and skips. */
    fun ensure(name: String, project: Project) {
        val repo = repoFor(name)
        val repoName = (repo as? Maven)?.name ?: (repo as Ivy).name
        if (!applyRepositoriesMode(project, repo, repoName)) return
        if (project.repositories.any { it.name == repoName }) return
        try {
            when (repo) {
                is Maven -> repo.registerOn(project.repositories)
                is Ivy -> repo.registerOn(project.repositories)
            }
        } catch (e: Exception) {
            if (isProjectRepoBlocked(e)) {
                throw GradleException(settingsBlockMessage(repo, repoName))
            }
            throw e
        }
    }

    /** The 1.x parameterized form — `parameterized("glassLauncher",
     *  "snapshots")` registers `maven.glass-launcher.net/snapshots/`. */
    fun parameterized(verb: String, suffix: String, project: Project) =
        ensure("$verb:$suffix", project)

    /**
     * Register the repo a maven [notation] resolves through, keyed
     * on its group — e.g. `net.ornithemc:feather:...` registers the
     * Ornithe maven. No-op for groups outside the catalog (those
     * resolve through the project's own declared repos).
     */
    fun ensureForNotation(notation: String, project: Project) {
        val group = notation.substringBefore(':')
        groupToRepo[group]?.let { ensure(it, project) }
    }

    /**
     * Check `dependencyResolutionManagement.repositoriesMode` —
     * returns false when a project-level register must be skipped
     * (already declared in settings, or ignored under
     * `PREFER_SETTINGS`). `FAIL_ON_PROJECT_REPOS` throws eagerly
     * with the settings block to add.
     */
    private fun applyRepositoriesMode(
        project: Project, repo: Any, repoName: String
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
                throw GradleException(settingsBlockMessage(repo, repoName))
            }
            RepositoriesMode.PREFER_SETTINGS -> {
                if (!declaredInSettings) {
                    project.logger.warn(
                        "[Unimined] repository '$repoName' is not in " +
                            "settings repositories and project-level " +
                            "repos are ignored under PREFER_SETTINGS " +
                            "— artifacts may fail to resolve"
                    )
                }
                return false
            }
            else -> return true
        }
    }

    private fun repoFor(name: String): Any {
        val verb = name.substringBefore(':')
        return if (':' in name) {
            val suffix = name.substringAfter(':')
            parameterized[verb]?.invoke(suffix)
                ?: error("unknown parameterized unimined repo '$verb'")
        } else {
            repos[name] ?: error("unknown unimined repo '$name'")
        }
    }

    /** The repo's registered Gradle name — used for the absent-check
     *  so parameterized entries dedupe by their resolved name. */
    private fun reposKey(name: String): String =
        (repoFor(name) as? Maven)?.name ?: (repoFor(name) as Ivy).name

    /** The registration-time fallback for the settings-mode check:
     *  Gradle itself rejects the project repo add under
     *  `FAIL_ON_PROJECT_REPOS`. */
    private fun isProjectRepoBlocked(e: Throwable): Boolean {
        val messages = generateSequence(e as Throwable?) { it.cause }
            .mapNotNull { it.message }.joinToString(" ")
        return "RepositoriesMode" in messages ||
            "settings repositories" in messages ||
            "project repositories" in messages
    }

    private fun settingsBlockMessage(repo: Any, repoName: String) =
        "unimined needs repository '$repoName' but this build " +
            "enforces dependencyResolutionManagement." +
            "repositoriesMode = FAIL_ON_PROJECT_REPOS. Declare it " +
            "in settings.gradle.kts:\n" +
            "  dependencyResolutionManagement {\n" +
            "    repositories {\n        " +
            repoSnippet(repo).replace("\n", "\n        ") +
            "\n    }\n  }"

    private fun repoSnippet(repo: Any): String = when (repo) {
        is Maven -> """maven("${repo.url}") {
            name = "${repo.name}"
        }"""
        is Ivy -> """ivy("${repo.url}") {
            patternLayout { artifact("${repo.artifactPattern}") }
        }"""
        else -> "// ${(repo as? Maven)?.name ?: (repo as? Ivy)?.name ?: repo.toString()}"
    }
}
