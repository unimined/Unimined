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
    private fun defaultMappingLines(spec: InitSpec, loader: String): List<MappingLine> {
        val mc = spec.mcVersion
        fun baked(axis: String): String? =
            Probed.expand("$axis:$mc").firstOrNull()
                ?: Probed.expand(axis).firstOrNull()
        val l = loader.lowercase()
        // baked lists are flat maven versions (`<mc>+build.N` /
        // `<mc>+<tail>`); the DSL arg is the tail — build int for
        // `*+build.N`, quoted commit/tag otherwise
        fun buildTail(axis: String): Int? = Probed.expand(axis)
            .firstOrNull { it.startsWith("$mc+build.") }
            ?.substringAfterLast("build.")?.toIntOrNull()
        fun plusTail(axis: String): String? = Probed.expand(axis)
            .firstOrNull { it.startsWith("$mc+") }
            ?.substringAfter('+')
        /** [verb] with its version arg as a gradle.properties ref
         *  (full coordinate) — or the literal [litArg] when
         *  [InitSpec.versionProps] is off. */
        fun versioned(verb: String, propKey: String, full: String?,
                      litArg: String): List<MappingLine> =
            if (spec.versionProps && full != null) listOf(MappingLine(
                "$verb(${verRef(spec, propKey, "\"$full\"")})",
                listOf(propKey to full)))
            else listOf(MappingLine("$verb($litArg)"))
        return when {
            // 26.x unobfuscated ships no mappings — checked before
            // any loader branch (fabric would emit `intermediary()`
            // unconditionally otherwise)
            InitSpec.isUnobfEra(mc) -> emptyList()
            l in listOf("babric", "bta") ->
                versioned("biny", "biny_mappings",
                    plusTail("biny")?.let { "$mc+$it" },
                    plusTail("biny")?.let { "\"$it\"" } ?: return emptyList())
            // ornithe uses feather even on b1.x — checked before
            // the b1-era nostalgia fallback; the v3 feather axis is
            // the gen-2 series so the gen var is pinned alongside
            l == "osl" || l == "ornithe" ->
                Probed.expand("feather").firstOrNull {
                    it.startsWith("$mc+build.") }?.let { full ->
                    listOf(MappingLine("ornitheGenVersion = 2")) +
                        versioned("feather", "feather_mappings", full,
                            full.substringAfterLast("build.").toIntOrNull()
                                ?.toString() ?: return emptyList())
                } ?: emptyList()
            mc.startsWith("b1.") ->
                versioned("nostalgia", "nostalgia_mappings",
                    Probed.expand("nostalgia").firstOrNull {
                        it.startsWith("$mc+build.") },
                    buildTail("nostalgia")?.toString() ?: return emptyList())
            l == "legacyfabric" ->
                Probed.expand("legacyyarn").firstOrNull {
                    it.startsWith("$mc+build.") }?.let { full ->
                    // current builds are gen-2 (`net.legacyfabric.v2`)
                    if (spec.versionProps) listOf(MappingLine(
                        "legacyYarn(${verRef(spec, "legacyyarn_mappings",
                            "\"$full\"")}, 2)",
                        listOf("legacyyarn_mappings" to full)))
                    else listOf(MappingLine("legacyYarn(" +
                            "${full.substringAfterLast("build.")}, 2)"))
                } ?: emptyList()
            l == "quilt" ->
                versioned("quilt", "quilt_mappings",
                    Probed.expand("quilt-mappings").firstOrNull {
                        it.startsWith("$mc+build.") },
                    buildTail("quilt-mappings")?.toString() ?: return emptyList())
            l == "fabric" -> listOfNotNull(
                MappingLine("intermediary()"),
                Probed.expand("yarn").firstOrNull {
                    it.startsWith("$mc+build.") }?.let { full ->
                    if (spec.versionProps) MappingLine(
                        "yarn(${verRef(spec, "yarn_mappings",
                            "\"$full\"")})",
                        listOf("yarn_mappings" to full))
                    else MappingLine("yarn(${
                        full.substringAfterLast("build.")})")
                })
            l == "rift" -> listOf(MappingLine("searge()"))
            l in listOf("forge", "fml", "lexforge", "cleanroom",
                "liteloader") -> baked("mcp")?.let {
                val p = it.split('_', limit = 2)
                // searge supplies the obf->srg class/member layer
                // mcp alone can't rename merged-obf classes
                listOf(MappingLine("searge()"),
                    if (spec.versionProps) propifyLine(spec,
                        "mcp(\"${p[0]}\", \"${p[1]}\")")
                    else MappingLine("mcp(\"${p[0]}\", \"${p[1]}\")"))
            }
            else -> null
        } ?: listOfNotNull(
            if (Probed.expand("mojmap").contains(mc))
                MappingLine("mojmap()") else null)
    }

    private fun mappingsBlock(spec: InitSpec, indent: String,
                              loader: String? = null): String {
        val lines = spec.vars.map { (k, v) -> "$k = $v" } +
            spec.mappings.map { propifyLine(spec, it).text }.ifEmpty {
                loader?.let { defaultMappingLines(spec, it).map { l -> l.text } }
                    ?: emptyList() }
        return if (lines.isEmpty()) "" else
            render("partials/mappings-block.gradle", mapOf(
                "MAPPINGS_LINES" to lines.joinToString("\n") { "    $it" }
            )).indented(indent)
    }

    /** gradle.properties entries the emitted build scripts resolve —
     *  mirrors exactly what `mappingsBlock`/`loader-line`/`version`
     *  emissions reference. */
    private fun versionPropEntries(spec: InitSpec): List<Pair<String, String>> {
        if (!spec.versionProps) return emptyList()
        val mappingProps = if (spec.mappings.isNotEmpty())
            spec.mappings.flatMap { propifyLine(spec, it).props }
        else spec.loaders.map { defaultMappingLines(spec, it.first) }
            .firstOrNull { it.isNotEmpty() }
            ?.flatMap { it.props } ?: emptyList()
        return listOf("minecraft_version" to spec.mcVersion) +
            spec.loaders.map { (l, v) ->
                "${l.lowercase().replace('-', '_')}_version" to v } +
            mappingProps +
            spec.loaders.filter { it.first == "fabric" }.mapNotNull {
                Probed.expand("fabric-api").firstOrNull { v ->
                    v.endsWith("+${spec.mcVersion}") }
                    ?.let { v -> "fabric_api_version" to v }
            }
    }

    /** `name = "v"` (kotlin) / `name.set("v")` (groovy) — `Property`
     *  targets need `.set` under Groovy; plain `var` targets keep `=`. */
    private fun propSet(spec: InitSpec, name: String, value: String) =
        if (spec.dsl == InitSpec.Dsl.KOTLIN) "$name = $value"
        else "$name.set($value)"

    /** `providers.gradleProperty("k").get()` — identical in both DSLs;
     *  falls back to [literal] when [InitSpec.versionProps] is off. */
    private fun verRef(spec: InitSpec, key: String, literal: String) =
        if (spec.versionProps) "providers.gradleProperty(\"$key\").get()"
        else literal

    /** One emitted mappings-block line + the gradle.properties entries
     *  its version args resolve through (empty → literal). */
    private data class MappingLine(val text: String,
        val props: List<Pair<String, String>> = emptyList())

    /** Mapping verbs whose version args can ride gradle.properties —
     *  prop key per arg position; `null` position stays literal
     *  (gen ints are constants, not versions). */
    private val MAPPING_ARG_PROPS: Map<String, List<String?>> = mapOf(
        "yarn" to listOf("yarn_mappings"),
        "quilt" to listOf("quilt_mappings"),
        "feather" to listOf("feather_mappings", null),
        "legacyYarn" to listOf("legacyyarn_mappings", null),
        "nostalgia" to listOf("nostalgia_mappings"),
        "biny" to listOf("biny_mappings"),
        "searge" to listOf("searge_mappings"),
        "mcp" to listOf("mcp_channel", "mcp_version"),
    )

    private val MAPPING_LINE_RE = Regex("""^(\w+)\((.*)\)$""")

    /** Rewrites a literal `verb(args)` line into a [MappingLine] —
     *  quoted args keep the String overload, bare ints get `.toInt()`.
     *  Unrecognized/arg-less verbs stay literal. */
    private fun propifyLine(spec: InitSpec, line: String): MappingLine {
        val m = MAPPING_LINE_RE.matchEntire(line.trim())
            ?: return MappingLine(line)
        val (verb, argsStr) = m.destructured
        val keys = MAPPING_ARG_PROPS[verb]
            ?: return MappingLine(line)
        val args = Regex("""("[^"]*"|\d+)""").findAll(argsStr)
            .map { it.value }.toList()
        if (!spec.versionProps || args.isEmpty() || args.size > keys.size)
            return MappingLine(line)
        val props = mutableListOf<Pair<String, String>>()
        val newArgs = args.mapIndexed { i, a ->
            val key = keys[i] ?: return@mapIndexed a
            props += key to a.removeSurrounding("\"")
            verRef(spec, key, a) +
                if (a.matches(Regex("\\d+"))) ".toInt()" else ""
        }
        return MappingLine("$verb(${newArgs.joinToString(", ")})", props)
    }

    /** `side = ...` line (empty when unset); enum values are emitted
     *  fully-qualified — generated scripts have no unimined imports. */
    private fun sideLine(spec: InitSpec): String = spec.side?.let {
        val up = it.uppercase()
        propSet(spec, "side",
            if (up in setOf("MERGED", "CLIENT", "SERVER"))
                "xyz.wagyourtail.unimined.api.spec.Side.$up"
            else it)
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
                    it == "paper" ->
                        "https://repo.papermc.io/repository/maven-public/"
                    it in listOf("bukkit", "craftbukkit", "spigot") ->
                        "https://hub.spigotmc.org/nexus/content/groups/public/"
                    // direct Nexus — dl.liteloader.com 302s through an
                    // insecure http redirector Gradle hard-fails on
                    it == "liteloader" ->
                        "https://repo.mumfrey.com/content/repositories/liteloader/"
                    // rift + dimdev mixin — dimdev.org offline,
                    // wagyourtail releases mirrors org.dimdev
                    it == "rift" -> "https://maven.wagyourtail.xyz/releases/"
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

    /** `java { toolchain }` pin (empty when unset). Kotlin DSL
     *  auto-imports `JavaLanguageVersion`; Groovy needs the FQN. */
    private fun toolchainBlock(spec: InitSpec): String =
        spec.toolchain?.let {
            val jlv = if (spec.dsl == InitSpec.Dsl.KOTLIN)
                "JavaLanguageVersion"
            else "org.gradle.jvm.toolchain.JavaLanguageVersion"
            """java {
    toolchain.languageVersion.set($jlv.of($it))
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
                if (spec.loaders.any { it.first.contains("fabric") } &&
                        !InitSpec.isUnobfEra(mc))
                    add("intermediary()")
                if (Probed.expand("mojmap").contains(mc)) add("mojmap()")
                else addAll(
                    spec.loaders.map { defaultMappingLines(spec, it.first) }
                        .firstOrNull { it.isNotEmpty() }?.map { it.text }
                        ?: emptyList())
            }
            // top-level minecraft{} IS the main env — shared
            // version/mappings live there; loader envs combineWith it
            val sharedLines = mutableListOf(
                propSet(spec, "version",
                    verRef(spec, "minecraft_version", "\"$mc\"")))
            sideLine(spec).takeIf { it.isNotEmpty() }
                ?.let { sharedLines.add(it) }
            if (sharedMappings.isNotEmpty()) {
                sharedLines.add("mappings {")
                sharedMappings.forEach { sharedLines.add("    $it") }
                sharedLines.add("}")
            }
            val common = sharedLines.joinToString("\n")
            spec.loaders.joinToString("\n", prefix = "$common\n") { (loader, version) ->
                render("partials/source-set-env.gradle", mapOf(
                    "LOADER" to loader,
                    "LOADER_VERSION" to verRef(spec,
                        "${loader.lowercase().replace('-', '_')}_version",
                        "\"$version\""),
                    "MC_VERSION" to "combined from common",
                    "SIDE" to "",
                    "MAPPINGS" to "",
                )).replace(
                    propSet(spec, "version", "\"combined from common\""),
                    "combineWith(\"main\")")
            }
        } else {
            render("partials/minecraft-env.gradle", mapOf(
                "MC_VERSION" to propSet(spec, "version",
                    verRef(spec, "minecraft_version",
                        "\"${spec.mcVersion}\"")),
                "SIDE" to sideLine(spec),
                "LOADER_LINES" to spec.loaders.joinToString("\n") { (l, v) ->
                    render("partials/loader-line.gradle", mapOf(
                        "LOADER" to l, "LOADER_VERSION" to verRef(spec,
                            "${l.lowercase().replace('-', '_')}_version",
                            "\"$v\"")
                    ))
                },
                "MAPPINGS" to mappingsBlock(spec, ""),
            ))
        }.indented("        ")

    /** `(\"fabric\", ...) — coords` trailing the `loaderApi` dep
     *  comment — prop ref + concrete coords when a fabric-api axis
     *  is baked, generic example otherwise. */
    private fun loaderApiComment(spec: InitSpec): String {
        val v = Probed.expand("fabric-api").firstOrNull {
            it.endsWith("+${spec.mcVersion}") }
            ?: return "(\"fabric\", \"0.92.2+1.20.1\")"
        return if (spec.versionProps)
            "(\"fabric\", providers.gradleProperty(" +
                    "\"fabric_api_version\").get())"
        else "(\"fabric\", \"$v\")"
    }

    /** `mod { }` block — metadata generated per env instead of files. */
    private fun modBlock(spec: InitSpec): String =
        // scoped under minecraft { } — per-env metadata
        if (!spec.metadataFromDsl) "" else "\n" + render(
            "partials/mod-block.gradle", mapOf(
                "PROP_LINES" to listOf(
                    propSet(spec, "id",
                        verRef(spec, "modid", "\"${spec.modId}\"")),
                    propSet(spec, "name", "\"${spec.name}\""),
                    propSet(spec, "version",
                        verRef(spec, "version", "\"${spec.version}\"")),
                ).joinToString("\n") { "    $it" },
                "LOADER_SECTIONS" to spec.loaders.joinToString("\n") { (l, _) ->
                    "    " + render("partials/mod-loader-section.gradle", mapOf(
                        "LOADER" to l,
                        // rift's riftmod.json carries entrypoints.main —
                        // the DSL route declares the class here
                        "LOADER_BODY" to if (l == "rift")
                            "\n        entrypoint(\"${spec.basePackage}." +
                                    "${entrypointClassName(spec, l)}\")\n    "
                        else ""
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
            "LOADER_API" to loaderApiComment(spec),
            "EXTRAS" to extras(spec),
        ))
    }

    /**
     * Optional extras appended to `build.gradle` — JarModAgent
     * client/server jar tasks when [InitSpec.jarmod].
     */
    fun extras(spec: InitSpec): String {
        val jarmodBlock = if (!spec.jarmod) "" else "\n" + render(
            if (spec.dsl == InitSpec.Dsl.KOTLIN) "partials/jarmod.gradle"
            else "partials/jarmod-groovy.gradle",
            mapOf("MODID" to spec.modId))
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
                    "MC_VERSION" to propSet(spec, "version",
                        verRef(spec, "minecraft_version",
                            "\"${spec.mcVersion}\"")),
                    "SIDE" to sideLine(spec),
                    "LOADER_LINES" to "",
                    "MAPPINGS" to mappingsBlock(spec, ""),
                ))
            } else {
                render("partials/minecraft-env.gradle", mapOf(
                    "MC_VERSION" to propSet(spec, "version",
                        verRef(spec, "minecraft_version",
                            "\"${spec.mcVersion}\"")),
                    "SIDE" to sideLine(spec),
                    "LOADER_LINES" to render("partials/loader-line.gradle",
                        mapOf("LOADER" to loader,
                            "LOADER_VERSION" to verRef(spec,
                                "${loader.lowercase().replace('-', '_')}" +
                                        "_version",
                                "\"${spec.loaders.first {
                                    it.first == loader }.second}\""))),
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
        val versionLine = "    " + propSet(spec, "version",
            verRef(spec, "minecraft_version",
                if (kts) "\"$spec.mcVersion\"" else "'${spec.mcVersion}'"))
        val loaderLines = spec.loaders.joinToString("\n") { (l, v) ->
            if (v.isEmpty()) "    loader(\"$l\")"
            else "    loader(\"$l\", \"$v\")"
        }
        val side = sideLine(spec).takeIf { it.isNotEmpty() }
            ?.let { "    $it\n" } ?: ""
        val inner = when {
            p == "nsss" || p.startsWith("custom:") ->
                versionLine + "\n" + side + fileCall.trimStart('\n') + loaderLines
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
        "VERSION_PROPS" to versionPropEntries(spec).joinToString("\n") {
            (k, v) -> "$k=$v"
        },
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
            // 1.20.1 resolves the `net.neoforged:forge` artifact —
            // still `net.minecraftforge.fml` packages, not the rename
            "neoforge" -> if (neoforgeForgeEra(spec.mcVersion)) "forge"
                          else "neoforge"
            "fabric", "quilt" -> loader.lowercase()
            // bukkit is the family name; the descriptors are
            // craftbukkit/spigot/paper — all JavaPlugin entrypoints
            "bukkit", "craftbukkit", "spigot", "paper" -> "bukkit"
            // liteloader = Tickable impl, rift = InitializationListener
            "liteloader", "rift" -> loader.lowercase()
            else -> "generic"
        }
        return render("entrypoint/$file.java", mapOf(
            "PACKAGE" to spec.basePackage,
            "CLASS" to entrypointClassName(spec, loader),
            "MODID" to spec.modId,
            "NAME" to spec.name,
            "VERSION" to spec.version,
        ))
    }

    /** `@Mod` annotation family for [mc]: modloader <1.3, cpw.mods
     *  1.3–1.7.10 (`@Mod.Init` ≤1.5.x, `@Mod.EventHandler` 1.6+),
     *  fml 1.8–1.12, modern ≥1.13. */
    private fun forgeEra(mc: String): String {
        val m = Regex("^(?:b)?1\\.(\\d+)").find(mc) ?: return "forge"
        val minor = m.groupValues[1].toIntOrNull() ?: return "forge"
        return when {
            mc.startsWith("b") || minor < 3 -> "forge-modloader"
            minor <= 5 -> "forge-cpw-init"
            minor <= 7 -> "forge-cpw"
            minor <= 12 -> "forge-fml"
            else -> "forge"
        }
    }

    /**
     * NeoForge shipped under `net.neoforged:forge` (the literal Forge
     * fork) for 1.20.1; the `net.neoforged` package rename + artifact
     * landed at 1.20.2 — mirror the descriptor's coord switch.
     */
    private fun neoforgeForgeEra(mc: String): Boolean {
        val m = Regex("^1\\.(\\d+)\\.(\\d+)").find(mc) ?: return false
        val (minor, patch) = m.groupValues[1].toInt() to m.groupValues[2].toInt()
        return minor < 20 || (minor == 20 && patch < 2)
    }

    /** Whether [loader] on [spec.mcVersion] uses the ModLoader-era
     *  `mod_*`/`BaseMod` entrypoint — a default-package class, not a
     *  packaged `@Mod` class. */
    fun isModLoaderEra(spec: InitSpec, loader: String): Boolean =
        (loader.equals("forge", true) || loader.equals("fg1", true)) &&
            forgeEra(spec.mcVersion) == "forge-modloader"

    /** Entrypoint class name for [loader] — `mod_<modid>` (default
     *  package) for ModLoader-era, `LiteMod<Pascal>` for liteloader
     *  (its own convention), PascalCase otherwise. */
    fun entrypointClassName(spec: InitSpec, loader: String): String =
        if (isModLoaderEra(spec, loader)) "mod_${spec.modId}"
        else if (loader.equals("liteloader", true))
            "LiteMod${mainClassName(spec)}"
        else mainClassName(spec)

    /**
     * Loader metadata stubs — `(path, content)` pairs emitted under
     * the env's `src/<ss>/resources/`.
     */
    fun metadata(spec: InitSpec, loader: String): List<Pair<String, String>> {
        val tokens = mapOf(
            "MODID" to spec.modId,
            "VERSION" to spec.version,
            "NAME" to spec.name,
            "ENTRYPOINT" to "${spec.basePackage}.${entrypointClassName(spec, loader)}",
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
            // user-authored stubs merge with the descriptors the
            // transform plugin emits (api-version etc. are stamped
            // there) — minimal fields only
            "bukkit", "craftbukkit", "spigot" -> listOf(
                "plugin.yml" to render("metadata/plugin.yml", tokens)
            )
            "paper" -> listOf(
                "paper-plugin.yml" to render("metadata/paper-plugin.yml", tokens)
            )
            else -> emptyList()
        }
    }

}
