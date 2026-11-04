package xyz.wagyourtail.unimined.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.NoOpCliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import xyz.wagyourtail.unimined.init.InitSpec
import xyz.wagyourtail.unimined.init.ProjectGenerator
import xyz.wagyourtail.unimined.init.VersionResolver
import xyz.wagyourtail.unimined.init.Io
import xyz.wagyourtail.unimined.init.userHome

/**
 * `unimined` — scaffold a new mod project.
 * `unimined new <name> [opts]` → ./<name>; `unimined init` → cwd.
 */
class Cli : NoOpCliktCommand(name = "unimined")

/** Shared scaffold options for `new`/`init`. */
abstract class Generate(name: String) : CliktCommand(name = name) {

    private val group by option("--group",
        help = "Maven group (required)")
    private val version by option("--version", "--mc",
        help = "mc/product version (required)")
    private val pluginVersion by option("--plugin-version",
        help = "unimined plugin version")
    private val loader by option("--loader",
        help = "loader name=version, repeatable or comma-separated")
        .multiple()
    private val mappings by option("--mappings",
        help = "mappings DSL line, repeatable").multiple()
    private val modid by option("--modid",
        help = "mod id (derived from name when omitted)")
    private val pkg by option("--package",
        help = "base package (group.modid default)")
    private val modVersion by option("--mod-version").default("1.0.0")
    private val dsl by option("--dsl",
        help = "build-script DSL: kotlin (default) or groovy")
    private val product by option("--product",
        help = "non-native product: bta, reindev, nsss, custom:<name>")
    private val dist by option("--dist",
        help = "dist jar for custom:/nsss product envs")
    private val modules by option("--modules",
        help = "emit common + per-loader subprojects").flag()
    private val jarmod by option("--jarmod",
        help = "JarModAgent transforms (client/server source sets)")
        .flag()
    private val jars by option("--jars",
        help = "jarmod jar sides: split (default) or merged")
    private val noWrapper by option("--no-wrapper",
        help = "don't emit gradlew/wrapper").flag()
    private val includeBuild by option("--include-build",
        help = "emit includeBuild(path) in pluginManagement")
    private val toolchain by option("--toolchain",
        help = "pin java { toolchain } to JDK n").int()
    private val extra by option("--extra",
        help = "raw build.gradle line(s), repeatable").multiple()
    private val side by option("--side",
        help = "env side: merged/client/server or raw expression")
    private val vars by option("--var",
        help = "var inside mappings { }: k=v, repeatable").multiple()
    private val gradle by option("--gradle",
        help = "Gradle wrapper version")
    private val out by option("--out",
        help = "output directory (overrides the subcommand default)")
    private val template by option("--template",
        help = "extract testing/<name>/ from a templates zip instead")
    private val templates by option("--templates",
        help = "templates zip path or URL (JVM only)")
    private val metadata by option("--metadata",
        help = "'dsl' emits a mod { } block instead of static files")
    private val offline by option("--offline",
        help = "skip version probes — disk cache + baked versions only")
        .flag()

    /** project name — `new` takes it as an argument, `init` defaults
     *  to `examplemod`. */
    abstract val projectName: String

    /** out dir when --out is unset. */
    abstract val defaultOut: String

    /** init writes in place — overwrite is implicit. */
    abstract val overwrite: Boolean

    /** Interactive value — `ask` runs when the option is absent;
     *  prompt failure (EOF/pipe) falls back to the require error. */
    protected fun ask(text: String, default: String? = null,
                      missing: () -> String): String = try {
        val suffix = default?.let { " [$it]" } ?: ""
        print("$text$suffix: ")
        readlnOrNull()?.trim()?.ifEmpty { null }
            ?: default ?: missing()
    } catch (e: Exception) { missing() }

    override fun run() {
        val pname = projectName // ask before any other prompt
        val resolver = VersionResolver(
            cacheDir = Io.path(userHome(), ".unimined", "cache"),
            offline = offline)
        /** `latest` / trailing-`*` prefix / exact — resolved against
         *  live-then-disk-then-baked lists for [axis]. */
        fun resolveSpec(axis: String, spec: String, forMc: String): String {
            val list by lazy { resolver.loaderVersions(axis, forMc) }
            return when {
                spec == "latest" -> list.firstOrNull()
                    ?: throw IllegalArgumentException(
                        "no versions known for '$axis'")
                spec.endsWith("*") ->
                    list.firstOrNull { it.startsWith(spec.dropLast(1)) }
                        ?: throw IllegalArgumentException(
                            "no version matching '$spec' for '$axis'")
                else -> spec
            }
        }

        // product envs take the product's own version list (bta etc.),
        // plain mc uses piston-meta
        val mc = (if (template != null) "" else (version
            ?: ask("Minecraft version", "latest") {
                throw IllegalArgumentException("missing required --version") }))
            .let { v ->
                when {
                    product != null -> resolveSpec(product!!, v, "")
                    v == "latest" -> resolver.latestMinecraft()
                    v.endsWith("*") -> resolver.minecraftVersions()
                        .firstOrNull { it.startsWith(v.dropLast(1)) }
                        ?: throw IllegalArgumentException(
                            "no mc version matching '$v'")
                    else -> v
                }
            }

        val loaderArgs = loader.ifEmpty {
            listOf(ask("Loaders (name[=version], comma-separated)",
                "fabric") { "fabric" }) }
        val loaders = loaderArgs.flatMap { it.split(',') }.map { entry ->
            val eq = entry.indexOf('=')
            val l = (if (eq > 0) entry.substring(0, eq) else entry).trim()
            val v = if (eq > 0) entry.substring(eq + 1).trim() else "latest"
            l to resolveSpec(l, v, mc)
        }

        /** `name(latest)` / `name(1.20.*)` mapping args resolve per-mc;
         *  `stable_12`-style results expand to `("stable", "12")`. */
        fun resolveMappingLine(line: String): String {
            val m = Regex("^\\s*(\\w+)\\((.*)\\)\\s*$")
                .find(line) ?: return line
            val name = m.groupValues[1]
            val args = m.groupValues[2].split(',').map { a ->
                val t = a.trim().trim('"', '\'')
                val r = resolveSpec(name, t, mc)
                if (r == t) a
                else Regex("^([a-zA-Z]+)_(\\d+)$").find(r)
                    ?.let { "\"${it.groupValues[1]}\", \"${it.groupValues[2]}\"" }
                    ?: "\"$r\""
            }
            return "$name(${args.joinToString(", ")})"
        }

        val spec = InitSpec(
            group = if (template != null) "com.example"
                else (group ?: ask("Group", "com.example") {
                    throw IllegalArgumentException("missing required --group") }),
            name = pname,
            version = modVersion,
            modid = modid,
            packageName = pkg ?: ask("Package",
                "${if (template != null) "com.example" else (group ?: "com.example")}.${modid ?: projectName.lowercase()}") {
                throw IllegalArgumentException("missing required --package") },
            mcVersion = mc,
            loaders = loaders,
            mappings = mappings.map { resolveMappingLine(it) },
            dsl = if (dsl == "groovy") InitSpec.Dsl.GROOVY
                else InitSpec.Dsl.KOTLIN,
            pluginVersion = (if (template != null) ""
                else (pluginVersion
                    ?: if (includeBuild != null) ""
                    else ask("Plugin version", "latest") {
                        throw IllegalArgumentException(
                            "missing required --plugin-version") }))
                .let { if (it == "latest") resolver.latestPlugin() else it },
            gradleVersion = gradle,
            templateName = template,
            metadataFromDsl = metadata == "dsl",
            layout = when {
                modules -> InitSpec.Layout.MULTILOADER
                product != null -> InitSpec.Layout.CUSTOM_PRODUCT
                else -> InitSpec.Layout.FLAT
            },
            product = product,
            distFile = dist,
            jarmod = jarmod,
            splitJars = jars != "merged",
            noWrapper = noWrapper,
            includeBuild = includeBuild,
            toolchain = toolchain,
            extraLines = extra,
            side = side?.uppercase(),
            vars = vars.associate {
                val eq = it.indexOf('=')
                require(eq > 0) { "--var wants k=v, got '$it'" }
                it.substring(0, eq) to it.substring(eq + 1)
            },
            templatesZip = templates?.takeIf { Io.exists(it) },
            templatesUrl = templates?.takeUnless { Io.exists(it) },
        )

        val o = out ?: defaultOut
        val written = ProjectGenerator.generate(spec, o, overwrite)
        echo("generated ${written.size} files in ${Io.resolve(o)}")
    }
}

class New : Generate("new") {
    private val name by argument(help = "project name").optional()
    private val pname by lazy {
        name ?: ask("Project name") {
            throw IllegalArgumentException("missing project name") } }
    override val projectName get() = pname
    override val defaultOut get() = pname
    override val overwrite get() = false
}

class Init : Generate("init") {
    private val name by option("--name",
        help = "project name (examplemod default)")
    override val projectName get() = name ?: "examplemod"
    override val defaultOut get() = "."
    override val overwrite get() = true
}

fun cliMain(args: Array<String>) =
    Cli().subcommands(New(), Init()).main(args)
