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

    /** project name — `new` takes it as an argument, `init` defaults
     *  to `examplemod`. */
    abstract val projectName: String

    /** out dir when --out is unset. */
    abstract val defaultOut: String

    /** init writes in place — overwrite is implicit. */
    abstract val overwrite: Boolean

    override fun run() {
        val resolver = VersionResolver(
            cacheDir = Io.path(userHome(), ".unimined", "cache"))
        val mc = (if (template != null) "" else (version
            ?: echo("error: missing required --version", err = true)
                .let { throw IllegalArgumentException("missing required --version") }))
            .let { if (it == "latest") resolver.latestMinecraft() else it }

        val loaders = loader.flatMap { it.split(',') }.map { entry ->
            val eq = entry.indexOf('=')
            require(eq > 0) { "--loader wants name=version, got '$entry'" }
            val l = entry.substring(0, eq).trim()
            val v = entry.substring(eq + 1).trim()
            l to if (v == "latest") resolver.latestLoader(l, mc) else v
        }

        val spec = InitSpec(
            group = if (template != null) "com.example"
                else (group ?: throw IllegalArgumentException(
                    "missing required --group")),
            name = projectName,
            version = modVersion,
            modid = modid,
            packageName = pkg,
            mcVersion = mc,
            loaders = loaders,
            mappings = mappings,
            dsl = if (dsl == "groovy") InitSpec.Dsl.GROOVY
                else InitSpec.Dsl.KOTLIN,
            pluginVersion = (if (template != null) ""
                else (pluginVersion
                    ?: if (includeBuild != null) ""
                    else throw IllegalArgumentException(
                        "missing required --plugin-version")))
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
    private val name by argument(help = "project name")
    override val projectName get() = name
    override val defaultOut get() = name
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
