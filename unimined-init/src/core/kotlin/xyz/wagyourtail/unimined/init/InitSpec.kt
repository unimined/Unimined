package xyz.wagyourtail.unimined.init

/**
 * Fully-resolved parameters for project generation. Version resolution
 * (`latest` → real manifest/metadata endpoints) is the caller's job —
 * every field here is already pinned.
 */
data class InitSpec(
    /** Gradle `group` + Maven coordinate group. */
    val group: String,
    /** Project name — also the Gradle `rootProject.name`. */
    val name: String,
    /** Mod version (1.0.0 default). */
    val version: String = "1.0.0",
    /** Mod id (lower_snake; derived from [name] when null). */
    val modid: String? = null,
    /** Base package for generated sources (group + modid default). */
    val packageName: String? = null,
    /** Minecraft version — pinned, e.g. `1.20.1`. */
    val mcVersion: String,
    /** Loader name + version pairs, e.g. `"fabric" to "0.16.0"`. */
    val loaders: List<Pair<String, String>>,
    /** Mappings block content (raw DSL lines, e.g. `yarn(10)`). */
    val mappings: List<String> = emptyList(),
    /** Build script DSL: `kts` or `groovy`. */
    val dsl: Dsl = Dsl.KOTLIN,
    /** Unimined plugin version — pinned. */
    val pluginVersion: String,
    /** Gradle wrapper version string (for wrapper.properties). */
    val gradleVersion: String? = null,
    /**
     * Template name inside a `unimined-templates` zip — when set,
     * generation extracts `testing/<name>/` wholesale instead of
     * the minimal emit; scaffold fields are ignored.
     */
    val templateName: String? = null,
    /** Local path to a `unimined-templates` zip. */
    val templatesZip: java.nio.file.Path? = null,
    /**
     * When true, emit a `mod { }` block in build.gradle — metadata is
     * generated per env by `generate<Env>ModMetadata` — instead of
     * static `src/<ss>/resources/` metadata files.
     */
    val metadataFromDsl: Boolean = false,
) {

    enum class Dsl(val scriptSuffix: String) {
        KOTLIN("kts"), GROOVY("")
    }

    /** Effective modid: explicit, or `name` normalized to lower_snake. */
    val modId: String
        get() = modid ?: name.lowercase()
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')

    /** Effective package: explicit, or `group.modid` (sanitized, flat). */
    val basePackage: String
        get() = packageName ?: "$group.${modId.filter { it != '_' }}"
            .lowercase().filter { it.isLetterOrDigit() || it == '.' }

    /** Effective Gradle version for wrapper.properties. */
    val wrapperGradleVersion: String
        get() = gradleVersion ?: DEFAULT_GRADLE_VERSION

    companion object {
        const val DEFAULT_GRADLE_VERSION = "8.10.2"
    }

}
