package xyz.wagyourtail.unimined.internal.minecraft.patch.forge.fg3.mcpconfig

import com.google.gson.JsonArray
import com.google.gson.JsonObject

data class McpConfigFunction(
    val version: String?,
    val classpath: List<String>?,
    val mainClass: String?,
    val args: List<ConfigValue>,
    val jvmArgs: List<ConfigValue>,
    val repo: String
) {
    companion object {
        private const val VERSION_KEY = "version"
        private const val ARGS_KEY = "args"
        private const val JVM_ARGS_KEY = "jvmargs"
        private const val REPO_KEY = "repo"

        fun fromJson(json: JsonObject, isSpec6: Boolean = false): McpConfigFunction {
            val args = if (json.has(ARGS_KEY)) configValuesFromJson(json.getAsJsonArray(ARGS_KEY)) else listOf()
            val jvmArgs = if (json.has(JVM_ARGS_KEY)) configValuesFromJson(json.getAsJsonArray(JVM_ARGS_KEY)) else listOf()
            return if (isSpec6) {
                val classpath = json.getAsJsonArray("classpath")?.map { it.asString } ?: listOf()
                val mainClass = json["main_class"]?.asString
                McpConfigFunction(null, classpath, mainClass, args, jvmArgs, "")
            } else {
                val version = json[VERSION_KEY].asString
                val repoEl = json[REPO_KEY]
                val repo = if (repoEl == null || repoEl.isJsonNull) "https://maven.neoforged.net/releases/" else repoEl.asString
                McpConfigFunction(version, null, null, args, jvmArgs, repo)
            }
        }

        private fun configValuesFromJson(json: JsonArray): List<ConfigValue> {
            return json.map { child -> ConfigValue.of(child.asString) }
        }
    }

    fun getDownloadUrl(): String {
        val v = version ?: error("getDownloadUrl() called on spec 6+ function without version")
        val parts = v.split(":".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
        val builder = StringBuilder()
        builder.append(repo)
        // Group:
        builder.append(parts[0].replace('.', '/')).append('/')
        // Name:
        builder.append(parts[1]).append('/')
        // Version:
        builder.append(parts[2]).append('/')
        // Artifact:
        builder.append(parts[1]).append('-').append(parts[2])

        // Classifier:
        if (parts.size >= 4) {
            builder.append('-').append(parts[3])
        }
        builder.append(".jar")
        return builder.toString()
    }
}