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
    val templatesZip: String? = null,
    /** Remote `unimined-templates` zip URL — downloaded to a temp
     *  file when [templatesZip] is unset. */
    val templatesUrl: String? = null,
    /**
     * When true, emit a `mod { }` block in build.gradle — metadata is
     * generated per env by `generate<Env>ModMetadata` — instead of
     * static `src/<ss>/resources/` metadata files.
     */
    val metadataFromDsl: Boolean = false,
    /** Project structure — see [Layout]. */
    val layout: Layout = Layout.FLAT,
    /**
     * Non-native product environment for [Layout.CUSTOM_PRODUCT]:
     * `bta`, `reindev`, `nsss`, or `custom:<name>`. Presets emit the
     * extension DSL (`bta { }`); `custom:` emits `custom("name") { }`.
     */
    val product: String? = null,
    /** Distribution jar path for `custom:` products / `nsss` —
     *  emitted as `file("...")` inside the env block. */
    val distFile: String? = null,
    /**
     * JarModAgent transforms — emit `client`/`server` source sets,
     * `clientJar`/`serverJar` tasks stamping `JarModAgent-Transforms`,
     * and `<modid>-<side>.transform` resource stubs (modloadermp shape).
     */
    val jarmod: Boolean = false,
    /**
     * Split client/server MC jars vs a merged jar — jarmod-era envs
     * produce one jar per side when [jarmod] is set and this is true.
     */
    val splitJars: Boolean = true,
    /** Skip emitting gradlew/wrapper files entirely (generated
     *  fixtures that run under a parent build's wrapper). */
    val noWrapper: Boolean = false,
    /**
     * `includeBuild("...")` path emitted in `pluginManagement` —
     * the project resolves the plugin from a composite build, so
     * the plugin declaration drops its version.
     */
    val includeBuild: String? = null,
    /** Java toolchain pin emitted as `java { toolchain }` — fixture
     *  builds pin the toolchain to keep bytecode parseable by old
     *  loader ASM versions. */
    val toolchain: Int? = null,
    /** Raw DSL lines appended to `build.gradle` — one-off fixture
     *  wiring the generator doesn't model (runs blocks, etc.). */
    val extraLines: List<String> = emptyList(),
    /** Environment side — `Side.MERGED`/`CLIENT`/`SERVER` or a raw
     *  expression (e.g. `mcSide` property ref). */
    val side: String? = null,
    /** Vars emitted inside `mappings { }` (e.g. ornitheGenVersion). */
    val vars: Map<String, String> = emptyMap(),
) {

    enum class Dsl(val scriptSuffix: String) {
        KOTLIN("kts"), GROOVY("")
    }

    /** Project structure the generator emits. */
    enum class Layout {
        /** One module; all envs in a single `minecraft { }` block. */
        FLAT,
        /** `common` + one subproject per loader (multi-loader). */
        MULTILOADER,
        /** One module; a non-native product env (`bta`/`custom:`). */
        CUSTOM_PRODUCT,
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
        const val DEFAULT_GRADLE_VERSION = "9.8.2"

        /** MC's post-obfuscation version scheme — `26.x` releases and
         *  their `26.x-snapshot-N`/`-pre`/`-rc` builds (bare major ≥ 26,
         *  no `1.` prefix) — ship unobfuscated: last version with
         *  `client_mappings` was 1.21.11, first without was
         *  26.1-snapshot-1, so no mapping layer applies. */
        fun isUnobfEra(mc: String): Boolean =
            (Regex("^(\\d+)").find(mc)?.groupValues?.get(1)
                ?.toIntOrNull() ?: 0) >= 26
    }

}
