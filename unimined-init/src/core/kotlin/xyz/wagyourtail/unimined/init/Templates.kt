package xyz.wagyourtail.unimined.init

/**
 * File-template functions backing [ProjectGenerator]. Each returns the
 * complete file content — deterministic string building only.
 */
internal object Templates {

    /** `settings.gradle(.kts)` — plugin + dependency repositories. */
    fun settings(spec: InitSpec): String = buildString {
        val kts = spec.dsl == InitSpec.Dsl.KOTLIN
        appendLine("pluginManagement {")
        appendLine("    repositories {")
        appendLine("        mavenLocal()")
        appendLine("        maven(\"https://maven.wagyourtail.xyz/releases/\")")
        appendLine("        maven(\"https://maven.wagyourtail.xyz/snapshots/\")")
        appendLine("        mavenCentral()")
        appendLine("        gradlePluginPortal()")
        appendLine("    }")
        appendLine("}")
        appendLine()
        appendLine("dependencyResolutionManagement {")
        appendLine("    repositories {")
        appendLine("        mavenLocal()")
        appendLine("        mavenCentral()")
        for (repo in loaderRepos(spec)) {
            appendLine("        maven(\"$repo\")")
        }
        appendLine("    }")
        appendLine("}")
        appendLine()
        appendLine("rootProject.name = \"${spec.name}\"")
    }

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

    /** `build.gradle(.kts)` — the unimined plugin + minecraft block. */
    fun build(spec: InitSpec): String = buildString {
        val kts = spec.dsl == InitSpec.Dsl.KOTLIN
        val pluginDecl = if (kts) {
            "id(\"xyz.wagyourtail.unimined\") version \"${spec.pluginVersion}\""
        } else {
            "id 'xyz.wagyourtail.unimined' version '${spec.pluginVersion}'"
        }
        appendLine("plugins {")
        appendLine("    java")
        appendLine("    $pluginDecl")
        appendLine("}")
        appendLine()
        appendLine("repositories {")
        appendLine("    mavenCentral()")
        for (repo in loaderRepos(spec)) {
            appendLine("    maven(\"$repo\")")
        }
        appendLine("}")
        appendLine()
        appendLine("unimined {")
        appendLine("    minecraft {")
        if (spec.loaders.size > 1) {
            for ((loader, version) in spec.loaders) {
                appendLine("        sourceSet(\"$loader\") {")
                appendLine("            version = \"${spec.mcVersion}\"")
                appendLine("            loader(\"$loader\", \"$version\")")
                if (spec.mappings.isNotEmpty()) {
                    appendLine("            mappings {")
                    for (line in spec.mappings) {
                        appendLine("                $line")
                    }
                    appendLine("            }")
                }
                appendLine("        }")
            }
        } else {
            appendLine("        version = \"${spec.mcVersion}\"")
            for ((loader, version) in spec.loaders) {
                appendLine("        loader(\"$loader\", \"$version\")")
            }
            if (spec.mappings.isNotEmpty()) {
                appendLine("        mappings {")
                for (line in spec.mappings) {
                    appendLine("            $line")
                }
                appendLine("        }")
            }
        }
        appendLine("    }")
        appendLine("}")
    }

    /** `gradle.properties` — Maven coordinates + mod metadata. */
    fun gradleProperties(spec: InitSpec): String = buildString {
        appendLine("org.gradle.jvmargs=-Xmx2G")
        appendLine()
        appendLine("group=${spec.group}")
        appendLine("version=${spec.version}")
        appendLine("modid=${spec.modId}")
    }

    /** `.gitignore` — standard Gradle/JDK/IDE entries. */
    val gitignore = """
        .gradle/
        build/
        out/
        .idea/
        *.iml
        .classpath
        .project
        .settings/
        run/
        logs/
        .unimined/
    """.trimIndent() + "\n"

    /** `gradle/wrapper/gradle-wrapper.properties`. */
    fun wrapperProperties(spec: InitSpec): String = """
        distributionBase=GRADLE_USER_HOME
        distributionPath=wrapper/dists
        distributionUrl=https\://services.gradle.org/distributions/gradle-${spec.wrapperGradleVersion}-bin.zip
        networkTimeout=10000
        validateDistributionUrl=true
        zipStoreBase=GRADLE_USER_HOME
        zipStorePath=wrapper/dists
    """.trimIndent() + "\n"

    /** PascalCase class name derived from the project name. */
    fun mainClassName(spec: InitSpec): String =
        spec.modId.split('_')
            .joinToString("") { it.replaceFirstChar(Char::uppercase) }

    /**
     * Entrypoint class for [loader] — a minimal Java skeleton with the
     * loader's init method stubbed.
     */
    fun mainClass(spec: InitSpec, loader: String): String {
        val pkg = spec.basePackage
        val cls = mainClassName(spec)
        val modId = spec.modId
        val (header, clazzDecl, entrypoint) = when (loader.lowercase()) {
            "fabric" -> Triple(
                "",
                "final class $cls implements net.fabricmc.api.ModInitializer",
                "@Override public void onInitialize() { }"
            )

            "quilt" -> Triple(
                "",
                "final class $cls implements org.quiltmc.loader.api.ModInitializer",
                "@Override public void onInitialize(org.quiltmc.qsl.base.api.entrypoint.ModInitializer.InitializationContext ctx) { }"
            )

            "neoforge" -> Triple(
                "@net.neoforged.fml.common.Mod(\"$modId\")",
                "final class $cls",
                "public $cls(net.neoforged.bus.api.IEventBus modBus) { }"
            )

            "forge" -> Triple(
                "@net.minecraftforge.fml.common.Mod(\"$modId\")",
                "final class $cls",
                "public $cls() { }"
            )

            else -> Triple("", "final class $cls", "public void init() { }")
        }
        return buildString {
            appendLine("package $pkg;")
            appendLine()
            appendLine("/** Mod entrypoint generated by unimined-init. */")
            if (header.isNotEmpty()) appendLine(header)
            appendLine("public $clazzDecl {")
            appendLine()
            appendLine("    $entrypoint")
            appendLine()
            appendLine("}")
        }
    }

    /**
     * Loader metadata stubs — `(path, content)` pairs emitted under
     * `src/main/resources/`.
     */
    fun metadata(spec: InitSpec, loader: String): List<Pair<String, String>> =
        when (loader.lowercase()) {
            "fabric" -> listOf("fabric.mod.json" to """
                {
                  "schemaVersion": 1,
                  "id": "${spec.modId}",
                  "version": "${spec.version}",
                  "name": "${spec.name}",
                  "entrypoints": {
                    "main": ["${spec.basePackage}.${mainClassName(spec)}"]
                  },
                  "depends": {
                    "minecraft": "~${spec.mcVersion}"
                  }
                }
                """.trimIndent() + "\n")

            "quilt" -> listOf("quilt.mod.json" to """
                {
                  "schema_version": 1,
                  "quilt_loader": {
                    "id": "${spec.modId}",
                    "version": "${spec.version}",
                    "name": "${spec.name}",
                    "entrypoints": {
                      "init": "${spec.basePackage}.${mainClassName(spec)}"
                    },
                    "depends": [{"id": "minecraft", "versions": ">=${spec.mcVersion}"}]
                  }
                }
                """.trimIndent() + "\n")

            "neoforge" -> listOf("META-INF/neoforge.mods.toml" to """
                modLoader = "javafml"
                loaderVersion = "[1,)"
                license = "MIT"
                [[mods]]
                  modId = "${spec.modId}"
                  version = "${spec.version}"
                  displayName = "${spec.name}"
                """.trimIndent() + "\n")

            "forge" -> listOf("META-INF/mods.toml" to """
                modLoader = "javafml"
                loaderVersion = "[1,)"
                license = "MIT"
                [[mods]]
                  modId = "${spec.modId}"
                  version = "${spec.version}"
                  displayName = "${spec.name}"
                """.trimIndent() + "\n")

            else -> emptyList()
        }

}
