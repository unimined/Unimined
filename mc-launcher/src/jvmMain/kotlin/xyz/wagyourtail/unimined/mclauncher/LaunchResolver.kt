package xyz.wagyourtail.unimined.mclauncher

import java.io.File
import java.nio.file.Path

/** The computed launch — main class, args, classpath, dirs. */
data class LaunchPlan(
    val mainClass: String,
    val jvmArgs: List<String>,
    val programArgs: List<String>,
    val classpath: List<Path>,
    val nativesDir: Path?,
    val environment: Map<String, String>
)

/** Everything the resolver needs that isn't in the version document. */
data class LaunchEnvironment(
    /** Ordered classpath entries (dev/prod artifact + libraries + mods). */
    val classpath: List<Path>,
    val gameDir: Path,
    val assetsDir: Path?,
    /** Asset index id to expose as `assets_index_name`. */
    val assetIndexId: String?,
    val nativesDir: Path?,
    val session: Session,
    /** Enabled rule features (`is_demo_user`, `has_custom_resolution`, ...). */
    val features: Set<String> = emptySet(),
    val launcherName: String = "unimined",
    val launcherVersion: String = "2.0.0",
    val resolutionWidth: Int? = null,
    val resolutionHeight: Int? = null,
    /** Extra `${name}` substitutions (e.g. loader-injected variables). */
    val extraVars: Map<String, String> = emptyMap()
)

/**
 * Computes a [LaunchPlan] from an effective version document plus the
 * run-side environment — evaluates argument rules and substitutes
 * `${...}` template variables.
 */
object LaunchResolver {

    fun resolve(document: VersionData, env: LaunchEnvironment): LaunchPlan {
        val variables = variables(document, env)
        val features = env.features.toMutableSet().apply {
            if (env.resolutionWidth != null || env.resolutionHeight != null) {
                add("has_custom_resolution")
            }
        }
        val programArgs = buildList {
            document.gameArguments?.forEach { entry ->
                if (entry.appliesTo(OperatingSystem.current(), features)) {
                    entry.value.mapTo(this) { substitute(it, variables) }
                }
            } ?: document.minecraftArguments?.split(' ')
                ?.mapTo(this) { substitute(it, variables) }
            if (env.resolutionWidth != null) {
                if (!any { it == "--width" }) {
                    add("--width"); add(env.resolutionWidth.toString())
                }
            }
            if (env.resolutionHeight != null) {
                if (!any { it == "--height" }) {
                    add("--height"); add(env.resolutionHeight.toString())
                }
            }
        }
        val jvmArgs = buildList {
            document.jvmArguments?.forEach { entry ->
                if (entry.appliesTo(OperatingSystem.current(), features)) {
                    entry.value.mapTo(this) { substitute(it, variables) }
                }
            }
        }
        return LaunchPlan(
            mainClass = document.mainClass
                ?: throw IllegalStateException("version document declares no mainClass"),
            jvmArgs = jvmArgs,
            programArgs = programArgs,
            classpath = env.classpath,
            nativesDir = env.nativesDir,
            environment = emptyMap()
        )
    }

    /** The `${...}` template variable map for a launch. */
    fun variables(document: VersionData, env: LaunchEnvironment): Map<String, String> = buildMap {
        put("version_name", document.id ?: "unimined")
        put("game_directory", env.gameDir.toAbsolutePath().toString())
        put("assets_root", (env.assetsDir ?: env.gameDir.resolve("assets")).toString())
        put(
            "assets_index_name", env.assetIndexId ?: document.assetIndex?.id
            ?: document.assets ?: document.id ?: "legacy"
        )
        put("auth_player_name", env.session.username)
        put("auth_uuid", env.session.uuid)
        put("auth_access_token", env.session.accessToken)
        put("auth_session", env.session.accessToken)
        put("user_type", env.session.userType)
        put("clientid", env.session.clientId)
        put("auth_xuid", env.session.xuid)
        put("user_properties", env.session.userProperties.entries.joinToString(",") {
            "{\"name\":\"${it.key}\",\"value\":\"${it.value}\"}"
        }.let { "{$it}" })
        put("natives_directory", env.nativesDir?.toAbsolutePath()?.toString() ?: "")
        put("launcher_name", env.launcherName)
        put("launcher_version", env.launcherVersion)
        put("classpath", env.classpath.joinToString(File.pathSeparator) { it.toString() })
        put("classpath_separator", File.pathSeparator)
        env.resolutionWidth?.let { put("resolution_width", it.toString()) }
        env.resolutionHeight?.let { put("resolution_height", it.toString()) }
        putAll(env.extraVars)
    }

    /** Substitute `${name}` occurrences; unknown names pass through. */
    fun substitute(argument: String, variables: Map<String, String>): String {
        var result = argument
        var start = result.indexOf("\${")
        while (start >= 0) {
            val end = result.indexOf('}', start)
            if (end < 0) break
            val key = result.substring(start + 2, end)
            val replacement = variables[key]
            if (replacement != null) {
                result = result.substring(0, start) + replacement + result.substring(end + 1)
                start = result.indexOf("\${", start + replacement.length)
            } else {
                start = result.indexOf("\${", end)
            }
        }
        return result
    }

}
