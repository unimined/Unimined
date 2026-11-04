package xyz.wagyourtail.unimined.init

/**
 * Resource-template rendering backing [ProjectGenerator]. Templates
 * live in `src/core/resources/templates/` with `@@TOKEN@@` markers —
 * this object builds the token maps and keeps the few looped
 * fragments (repo lines, per-loader env blocks) as indented
 * pre-built strings injected via tokens.
 */
internal object Templates {

    private fun resource(path: String): String {
        val p = "/templates/$path" +
            (if (path.endsWith(".gradle")) ".tpl" else "")
        return TemplateFiles[p].decodeToString()
    }

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
    /** Baked era-default mapping lines for [loader] on spec.mcVersion —
     *  deterministic (baked lists only, no runtime probe); empty on
     *  unobfuscated 26.x or unknown loaders. */
    private fun defaultMappingLines(loader: String, mc: String): List<String> {
        fun baked(axis: String): String? =
            Probed.expand("$axis:$mc").firstOrNull()
                ?: Probed.expand(axis).firstOrNull()
        val l = loader.lowercase()
        return when {
            l in listOf("babric", "bta") -> baked("biny")
                ?.let { listOf("biny(\"$it\")") }
            mc.startsWith("b1.") -> baked("nostalgia")
                ?.let { listOf("nostalgia(\"$it\")") }
            l == "legacyfabric" -> baked("legacyyarn")
                ?.let { listOf("legacyYarn(\"$it\")") }
            l == "osl" -> baked("feather")
                ?.let { listOf("feather(\"$it\")") }
            l == "quilt" -> baked("hashed")
                ?.let { listOf("hashed(\"$it\")") }
            l == "fabric" -> listOfNotNull(
                "intermediary()",
                baked("yarn")?.let { "yarn(\"$it\")" })
            l in listOf("forge", "fml") -> baked("mcp")?.let {
                val p = it.split('_', limit = 2)
                listOf("mcp(\"${p[0]}\", \"${p[1]}\")")
            }
            else -> null
        } ?: listOfNotNull(
            if (Probed.expand("mojmap").contains(mc)) "mojmap()" else null)
    }

    private fun mappingsBlock(spec: InitSpec, indent: String,
                              loader: String? = null): String {
        val lines = spec.vars.map { (k, v) -> "$k = $v" } +
            spec.mappings.ifEmpty {
                loader?.let { defaultMappingLines(it, spec.mcVersion) }
                    ?: emptyList() }
        return if (lines.isEmpty()) "" else
            render("partials/mappings-block.gradle", mapOf(
                "MAPPINGS_LINES" to lines.joinToString("\n") { "    $it" }
            )).indented(indent)
    }

    /** `side = ...` line (empty when unset); Side.* gets enum-wrapped. */
    private fun sideLine(spec: InitSpec): String = spec.side?.let {
        if (it in setOf("MERGED", "CLIENT", "SERVER")) "side = Side.$it"
        else "side = $it"
    } ?: ""

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

    /** `includeBuild` line for composite resolution (empty when absent). */
    private fun inclBuild(spec: InitSpec): String =
        spec.includeBuild?.let { "    includeBuild(\"$it\")\n" } ?: ""

    /** Plugin declaration — versionless when a composite supplies it. */
    private fun pluginDecl(spec: InitSpec): String {
        val kts = spec.dsl == InitSpec.Dsl.KOTLIN
        val base = if (kts) "id(\"xyz.wagyourtail.unimined\")"
            else "id 'xyz.wagyourtail.unimined'"
        return if (spec.includeBuild != null) base
            else base + (if (kts) " version \"${spec.pluginVersion}\""
                else " version '${spec.pluginVersion}'")
    }

    /** `java { toolchain }` pin (empty when unset). */
    private fun toolchainBlock(spec: InitSpec): String =
        spec.toolchain?.let { """java {
    toolchain.languageVersion.set(JavaLanguageVersion.of($it))
}
""" } ?: ""

    /** `settings.gradle(.kts)` — plugin + dependency repositories. */
    fun settings(spec: InitSpec): String = render("settings.gradle", mapOf(
        "INCL_BUILD" to inclBuild(spec),
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
            // loader-less `common` env carries version/side and the MC
            // classpath; each loader env `combineWith`s it so shared code
            // sees Minecraft and flows into every loader jar
            val mc = spec.mcVersion
            // shared namespace for common code — loader envs inherit
            // it via combineWith; intermediary rides along only when a
            // fabric-family env needs the runtime chain
            val sharedMappings = buildList {
                if (spec.loaders.any { it.first.contains("fabric") })
                    add("intermediary()")
                if (Probed.expand("mojmap").contains(mc)) add("mojmap()")
                else addAll(
                    spec.loaders.map { defaultMappingLines(it.first, mc) }
                        .firstOrNull { it.isNotEmpty() } ?: emptyList())
            }
            val commonLines = mutableListOf(
                "sourceSet(\"common\") {",
                "    version = \"$mc\"")
            sideLine(spec).takeIf { it.isNotEmpty() }
                ?.let { commonLines.add("    $it") }
            if (sharedMappings.isNotEmpty()) {
                commonLines.add("    mappings {")
                sharedMappings.forEach { commonLines.add("        $it") }
                commonLines.add("    }")
            }
            commonLines.add("}")
            val common = commonLines.joinToString("\n")
            spec.loaders.joinToString("\n", prefix = "$common\n") { (loader, version) ->
                render("partials/source-set-env.gradle", mapOf(
                    "LOADER" to loader,
                    "LOADER_VERSION" to version,
                    "MC_VERSION" to "combined from common",
                    "SIDE" to "",
                    "MAPPINGS" to "",
                )).replace(
                    "version = \"combined from common\"",
                    "combineWith(\"common\")")
            }
        } else {
            render("partials/minecraft-env.gradle", mapOf(
                "MC_VERSION" to spec.mcVersion,
                "SIDE" to sideLine(spec),
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
        // scoped under minecraft { } — per-env metadata
        if (!spec.metadataFromDsl) "" else "\n" + render(
            "partials/mod-block.gradle", mapOf(
                "MODID" to spec.modId,
                "NAME" to spec.name,
                "VERSION" to spec.version,
                "LOADER_SECTIONS" to spec.loaders.joinToString("\n") { (l, _) ->
                    "    " + render("partials/mod-loader-section.gradle", mapOf(
                        "LOADER" to l
                    ))
                },
            )
        ).indented("    ")

    /** `build.gradle(.kts)` — the unimined plugin + minecraft block. */
    fun build(spec: InitSpec): String {
        val kts = spec.dsl == InitSpec.Dsl.KOTLIN
        return render("build.gradle", mapOf(
            "PLUGIN_DECL" to pluginDecl(spec),
            "TOOLCHAIN" to toolchainBlock(spec),
            "REPOS" to loaderRepos(spec).joinToString("\n") {
                "    maven(\"$it\")"
            },
            "MINECRAFT_BODY" to minecraftBody(spec),
            "MOD_BLOCK" to modBlock(spec),
            "EXTRAS" to extras(spec),
        ))
    }

    /**
     * Optional extras appended to `build.gradle` — JarModAgent
     * client/server jar tasks when [InitSpec.jarmod].
     */
    fun extras(spec: InitSpec): String {
        val jarmodBlock = if (!spec.jarmod) "" else "\n" + render(
            "partials/jarmod.gradle", mapOf("MODID" to spec.modId))
        val extra = if (spec.extraLines.isEmpty()) ""
            else "\n" + spec.extraLines.joinToString("\n")
        return jarmodBlock + extra
    }

    /** Jar sides for jarmod emits — client+server when split. */
    fun jarSides(spec: InitSpec): List<String> =
        if (spec.splitJars) listOf("client", "server") else listOf("client")

    // ---- multiloader layout ----

    /** Modules a MULTILOADER project emits — `common` + one per loader. */
    fun modules(spec: InitSpec): List<String> =
        listOf("common") + spec.loaders.map { it.first }

    /** Root `settings.gradle` for MULTILOADER — `include()`s every module. */
    fun multiloaderSettings(spec: InitSpec): String =
        render("multiloader/settings.gradle", mapOf(
            "INCL_BUILD" to inclBuild(spec),
            "DEP_REPOS" to loaderRepos(spec).joinToString("\n") {
                "        maven(\"$it\")"
            },
            "NAME" to spec.name,
            "INCLUDES" to modules(spec).joinToString("\n") {
                "include(\"$it\")"
            },
        ))

    /**
     * A MULTILOADER module's `build.gradle` — `common` ([loader] null)
     * gets a loader-less env for the MC classpath; loader modules get
     * their env plus a dependency on `:common`.
     */
    fun multiloaderModule(spec: InitSpec, loader: String?): String {
        return render("multiloader/module.gradle", mapOf(
            "PLUGIN_DECL" to pluginDecl(spec),
            "TOOLCHAIN" to toolchainBlock(spec),
            "REPOS" to loaderRepos(spec).joinToString("\n") {
                "    maven(\"$it\")"
            },
            "MINECRAFT_BODY" to if (loader == null) {
                render("partials/minecraft-env.gradle", mapOf(
                    "MC_VERSION" to spec.mcVersion,
                    "SIDE" to sideLine(spec),
                    "LOADER_LINES" to "",
                    "MAPPINGS" to mappingsBlock(spec, ""),
                ))
            } else {
                render("partials/minecraft-env.gradle", mapOf(
                    "MC_VERSION" to spec.mcVersion,
                    "SIDE" to sideLine(spec),
                    "LOADER_LINES" to render("partials/loader-line.gradle",
                        mapOf("LOADER" to loader,
                            "LOADER_VERSION" to spec.loaders.first {
                                it.first == loader }.second)),
                    "MAPPINGS" to mappingsBlock(spec, ""),
                ))
            }.indented("        "),
            "DEPENDENCIES" to if (loader == null) "" else """
                |
                |dependencies {
                |    implementation(project(":common"))
                |    // modImplementation("maven:coord") — remapped mods
                |}
                """.trimMargin(),
        ))
    }

    // ---- custom-product layout ----

    /**
     * The `unimined { }` body for CUSTOM_PRODUCT — preset DSL for
     * bta/reindev/nsss, `custom("name")` otherwise; `nsss` and
     * `custom:` take a `file("...")` from [InitSpec.distFile].
     */
    fun productEnv(spec: InitSpec): String {
        val p = spec.product ?: error("CUSTOM_PRODUCT requires product")
        val kts = spec.dsl == InitSpec.Dsl.KOTLIN
        val fileCall = spec.distFile?.let {
            if (kts) "\n    file(\"$it\")" else "\n    file('$it')"
        } ?: ""
        val versionLine = spec.mcVersion.let {
            if (kts) "    version = \"$it\"" else "    version = '$it'"
        }
        val loaderLines = spec.loaders.joinToString("\n") { (l, v) ->
            if (v.isEmpty()) "    loader(\"$l\")"
            else "    loader(\"$l\", \"$v\")"
        }
        val side = sideLine(spec).takeIf { it.isNotEmpty() }
            ?.let { "    $it\n" } ?: ""
        val inner = when {
            p == "nsss" || p.startsWith("custom:") ->
                side + fileCall.trimStart('\n') + loaderLines
            else -> versionLine + "\n" + side + loaderLines
        }
        return when {
            p == "bta" || p == "reindev" -> "$p {\n$inner\n}"
            p == "nsss" -> "nsss {\n$inner\n}"
            p.startsWith("custom:") ->
                "custom(\"${p.removePrefix("custom:")}\") {\n$inner\n}"
            else -> error("unknown product '$p' — expected bta, reindev, " +
                    "nsss, or custom:<name>")
        }
    }

    /** `build.gradle` for CUSTOM_PRODUCT — product env instead of
     *  the minecraft block. */
    fun buildProduct(spec: InitSpec): String {
        return render("build-product.gradle", mapOf(
            "PLUGIN_DECL" to pluginDecl(spec),
            "REPOS" to loaderRepos(spec).joinToString("\n") {
                "    maven(\"$it\")"
            },
            "TOOLCHAIN" to toolchainBlock(spec),
            "ENV" to productEnv(spec).indented("    "),
            "MOD_BLOCK" to modBlock(spec),
            "EXTRAS" to extras(spec),
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
    /**
     * Entrypoint class for [loader] — a minimal Java skeleton with the
     * loader's init method stubbed. Forge picks the era-appropriate
     * `@Mod` annotation (cpw ≤1.7.10, fml 1.8–1.12, modern ≥1.13).
     */
    fun mainClass(spec: InitSpec, loader: String): String {
        val file = when (loader.lowercase()) {
            "forge", "fg1" -> forgeEra(spec.mcVersion)
            "fabric", "quilt", "neoforge" -> loader.lowercase()
            else -> "generic"
        }
        return render("entrypoint/$file.java", mapOf(
            "PACKAGE" to spec.basePackage,
            "CLASS" to mainClassName(spec),
            "MODID" to spec.modId,
            "NAME" to spec.name,
            "VERSION" to spec.version,
        ))
    }

    /** `@Mod` annotation family for [mc]: cpw.mods ≤1.7.10, fml ≤1.12. */
    private fun forgeEra(mc: String): String {
        val m = Regex("^(?:b)?1\\.(\\d+)").find(mc) ?: return "forge"
        val minor = m.groupValues[1].toIntOrNull() ?: return "forge"
        return when {
            mc.startsWith("b") || minor <= 7 -> "forge-cpw"
            minor <= 12 -> "forge-fml"
            else -> "forge"
        }
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
