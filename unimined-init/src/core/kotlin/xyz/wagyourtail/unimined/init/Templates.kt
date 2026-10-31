package xyz.wagyourtail.unimined.init

/**
 * Resource-template rendering backing [ProjectGenerator]. Templates
 * live in `src/core/resources/templates/` with `@@TOKEN@@` markers —
 * this object builds the token maps and keeps the few looped
 * fragments (repo lines, per-loader env blocks) as indented
 * pre-built strings injected via tokens.
 */
internal object Templates {

    private fun resource(path: String): String =
        javaClass.getResourceAsStream("/templates/$path")
            ?.use { it.readBytes().decodeToString() }
            ?: error("missing template resource /templates/$path")

    /** Render a template — unresolved `@@TOKEN@@` markers fail. */
    private fun render(path: String, tokens: Map<String, String>): String {
        var out = resource(path)
        for ((k, v) in tokens) out = out.replace("@@$k@@", v)
        val leftover = Regex("@@\\w+@@").find(out)
        require(leftover == null) {
            "unresolved template marker ${leftover!!.value} in $path"
        }
        return out
    }

    /** Indent every non-blank line of a rendered partial. */
    private fun String.indented(indent: String): String =
        lineSequence().joinToString("\n") {
            if (it.isBlank()) it else indent + it
        }

    /** `mappings { }` partial — empty when no mappings configured. */
    private fun mappingsBlock(spec: InitSpec, indent: String): String =
        if (spec.mappings.isEmpty()) "" else
            render("partials/mappings-block.gradle", mapOf(
                "MAPPINGS_LINES" to spec.mappings.joinToString("\n") {
                    "    $it"
                }
            )).indented(indent)

    /** Loader-specific Maven repos needed for metadata resolution. */
    private fun loaderRepos(spec: InitSpec): List<String> =
        spec.loaders.map { (loader, _) -> loader.lowercase() }
            .mapNotNull {
                when {
                    it.contains("fabric") -> "https://maven.fabricmc.net/"
                    it.contains("forge") && !it.contains("neo") ->
                        "https://maven.minecraftforge.net/"

                    it.contains("neo") -> "https://maven.neoforged.net/releases"
                    else -> null
                }
            }.distinct()

    /** `settings.gradle(.kts)` — plugin + dependency repositories. */
    fun settings(spec: InitSpec): String = render("settings.gradle", mapOf(
        "DEP_REPOS" to loaderRepos(spec).joinToString("\n") {
            "        maven(\"$it\")"
        },
        "NAME" to spec.name,
    ))

    /**
     * The `minecraft { }` body — composed from partials; single-loader
     * emits `minecraft-env`, multi-loader emits a `source-set-env`
     * block per loader (§10 layout).
     */
    private fun minecraftBody(spec: InitSpec): String =
        if (spec.loaders.size > 1) {
            spec.loaders.joinToString("\n") { (loader, version) ->
                render("partials/source-set-env.gradle", mapOf(
                    "LOADER" to loader,
                    "LOADER_VERSION" to version,
                    "MC_VERSION" to spec.mcVersion,
                    "MAPPINGS" to mappingsBlock(spec, "    "),
                ))
            }
        } else {
            render("partials/minecraft-env.gradle", mapOf(
                "MC_VERSION" to spec.mcVersion,
                "LOADER_LINES" to spec.loaders.joinToString("\n") { (l, v) ->
                    render("partials/loader-line.gradle", mapOf(
                        "LOADER" to l, "LOADER_VERSION" to v
                    ))
                },
                "MAPPINGS" to mappingsBlock(spec, ""),
            ))
        }.indented("        ")

    /** `mod { }` block — metadata generated per env instead of files. */
    private fun modBlock(spec: InitSpec): String =
        if (!spec.metadataFromDsl) "" else "\n" + render(
            "partials/mod-block.gradle", mapOf(
                "MODID" to spec.modId,
                "NAME" to spec.name,
                "VERSION" to spec.version,
                "LOADER_SECTIONS" to spec.loaders.joinToString("\n") { (l, _) ->
                    render("partials/mod-loader-section.gradle", mapOf(
                        "LOADER" to l
                    ))
                },
            )
        ).indented("    ")

    /** `build.gradle(.kts)` — the unimined plugin + minecraft block. */
    fun build(spec: InitSpec): String {
        val kts = spec.dsl == InitSpec.Dsl.KOTLIN
        return render("build.gradle", mapOf(
            "PLUGIN_DECL" to if (kts) {
                "id(\"xyz.wagyourtail.unimined\") version \"${spec.pluginVersion}\""
            } else {
                "id 'xyz.wagyourtail.unimined' version '${spec.pluginVersion}'"
            },
            "REPOS" to loaderRepos(spec).joinToString("\n") {
                "    maven(\"$it\")"
            },
            "MINECRAFT_BODY" to minecraftBody(spec),
            "MOD_BLOCK" to modBlock(spec),
        ))
    }

    /** `gradle.properties` — Maven coordinates + mod metadata. */
    fun gradleProperties(spec: InitSpec): String = render("gradle.properties", mapOf(
        "GROUP" to spec.group,
        "VERSION" to spec.version,
        "MODID" to spec.modId,
    ))

    /** `.gitignore` — standard Gradle/JDK/IDE entries (static). */
    val gitignore get() = resource("gitignore")

    /** `gradle/wrapper/gradle-wrapper.properties`. */
    fun wrapperProperties(spec: InitSpec): String =
        render("gradle-wrapper.properties", mapOf(
            "GRADLE_VERSION" to spec.wrapperGradleVersion
        ))

    /** PascalCase class name derived from the project name. */
    fun mainClassName(spec: InitSpec): String =
        spec.modId.split('_')
            .joinToString("") { it.replaceFirstChar(Char::uppercase) }

    /**
     * Entrypoint class for [loader] — a minimal Java skeleton with the
     * loader's init method stubbed.
     */
    fun mainClass(spec: InitSpec, loader: String): String {
        val file = when (loader.lowercase()) {
            "fabric", "quilt", "neoforge", "forge" -> loader.lowercase()
            else -> "generic"
        }
        return render("entrypoint/$file.java", mapOf(
            "PACKAGE" to spec.basePackage,
            "CLASS" to mainClassName(spec),
            "MODID" to spec.modId,
        ))
    }

    /**
     * Loader metadata stubs — `(path, content)` pairs emitted under
     * the env's `src/<ss>/resources/`.
     */
    fun metadata(spec: InitSpec, loader: String): List<Pair<String, String>> {
        val tokens = mapOf(
            "MODID" to spec.modId,
            "VERSION" to spec.version,
            "NAME" to spec.name,
            "ENTRYPOINT" to "${spec.basePackage}.${mainClassName(spec)}",
            "MC_VERSION" to spec.mcVersion,
        )
        return when (loader.lowercase()) {
            "fabric" -> listOf(
                "fabric.mod.json" to render("metadata/fabric.mod.json", tokens)
            )
            "quilt" -> listOf(
                "quilt.mod.json" to render("metadata/quilt.mod.json", tokens)
            )
            "neoforge" -> listOf(
                "META-INF/neoforge.mods.toml" to
                        render("metadata/neoforge.mods.toml", tokens)
            )
            "forge" -> listOf(
                "META-INF/mods.toml" to render("metadata/mods.toml", tokens)
            )
            else -> emptyList()
        }
    }

}
