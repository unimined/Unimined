package xyz.wagyourtail.unimined.cli

import xyz.wagyourtail.unimined.init.InitSpec
import xyz.wagyourtail.unimined.init.ProjectGenerator
import xyz.wagyourtail.unimined.init.VersionResolver
import xyz.wagyourtail.unimined.init.cwd
import xyz.wagyourtail.unimined.init.eprintln
import xyz.wagyourtail.unimined.init.userHome
import xyz.wagyourtail.unimined.init.Io
import kotlin.system.exitProcess

private const val USAGE = """
unimined — scaffold a new mod project

usage:
  unimined new <name> [options]     generate into ./<name>
  unimined init [options]           generate into the current directory

options:
  --group <g>            Maven group (required)
  --version <ver>        mc/product version (required; --mc is an alias)
  --plugin-version <v>   unimined plugin version (required)
  --loader <l=v>         loader, repeatable or comma-separated:
                         --loader fabric=0.16.0 --loader forge=47.2.0
  --mappings <dsl>       mappings DSL line, repeatable: --mappings "yarn(10)"
  --modid <id>           mod id (derived from name when omitted)
  --package <pkg>        base package (group.modid default)
  --mod-version <v>      mod version (1.0.0 default)
  --dsl <kotlin|groovy>  build-script DSL (kotlin default)
  --product <id>         non-native product env (default native):
                         bta, reindev, nsss, custom:<name>
  --dist <path>          dist jar for custom:/nsss product envs
  --modules              emit common + per-loader subprojects
                         (flat single-module layout is the default)
  --jarmod               JarModAgent transforms: client/server source
                         sets + per-side jars (modloadermp shape)
  --jars <split|merged>  jarmod jar sides (split default)
  --no-wrapper           don't emit gradlew/wrapper (fixtures run
                         under a parent build's wrapper)
  --include-build <p>    emit includeBuild(p) in pluginManagement —
                         plugin resolved from a composite (drops
                         the plugin version)
  --toolchain <n>        pin java { toolchain } to JDK n
  --extra <dsl>          raw build.gradle line(s), repeatable —
                         one-off fixture wiring (runs blocks, etc.)
  --side <merged|client|server|expr>
                         env side (Side.* enum or raw expression)
  --var <k=v>            var inside mappings { }, repeatable
  --gradle <ver>         Gradle wrapper version (8.10.2 default)
  --out <dir>            output directory (overrides the subcommand default)

`latest` resolves against real manifests (piston-meta, fabric-meta,
loader maven-metadata.xml, wagyourtail maven) — pin explicitly to
skip resolution.

--template <name> --templates <zip> extracts testing/<name>/ from a
unimined-templates.zip instead of the minimal emit (scaffold flags
unused).

--metadata dsl emits a `mod { }` block — metadata generated per env
by unimined-mod's emitters — instead of static files (default).
"""

private val BOOLS = setOf("--modules", "--jarmod", "--no-wrapper")

fun main(args: Array<String>) {
    if (args.isEmpty()) {
        print(USAGE); return
    }
    try {
        run(args)
    } catch (e: IllegalArgumentException) {
        eprintln("error: ${e.message}")
        exitProcess(2)
    }
}

private fun run(args: Array<String>) {
    val rest = args.toMutableList()
    val command = rest.removeAt(0)
    require(command == "new" || command == "init") { "unknown command '$command'\n$USAGE" }

    var name: String? = null
    if (command == "new") {
        name = rest.firstOrNull()?.takeIf { !it.startsWith("--") }
            ?.also { rest.removeAt(0) }
            ?: throw IllegalArgumentException("`new` requires a project name")
    }

    val flags = linkedMapOf<String, MutableList<String>>()
    var i = 0
    while (i < rest.size) {
        val a = rest[i]
        require(a.startsWith("--")) { "unexpected argument '$a'" }
        if (a in BOOLS) { flags.getOrPut(a) { mutableListOf() } += "true"; i++; continue }
        val eq = a.indexOf('=')
        if (eq > 0) {
            flags.getOrPut(a.substring(0, eq)) { mutableListOf() } += a.substring(eq + 1)
            i++
        } else {
            require(i + 1 < rest.size) { "flag '$a' requires a value" }
            flags.getOrPut(a) { mutableListOf() } += rest[i + 1]
            i += 2
        }
    }

    fun one(flag: String): String? = flags[flag]?.lastOrNull()
    fun need(flag: String): String =
        one(flag) ?: throw IllegalArgumentException("missing required $flag")

    // --template <name> extracts testing/<name>/ from a templates
    // zip instead of the minimal emit — scaffold flags unused
    val template = one("--template")

    val resolver = VersionResolver(
        cacheDir = Io.path(userHome(), ".unimined", "cache"
        )
    )
    val mc = (if (template != null) "" else
        (one("--version") ?: one("--mc")
            ?: throw IllegalArgumentException("missing required --version")))
        .let { if (it == "latest") resolver.latestMinecraft() else it }

    val loaders = flags["--loader"].orEmpty()
        .flatMap { it.split(',') }
        .map { entry ->
            val eq = entry.indexOf('=')
            require(eq > 0) { "--loader wants name=version, got '$entry'" }
            val l = entry.substring(0, eq).trim()
            val v = entry.substring(eq + 1).trim()
            l to if (v == "latest") resolver.latestLoader(l, mc) else v
        }

    val spec = InitSpec(
        group = if (template != null) "com.example" else need("--group"),
        name = name ?: xyz.wagyourtail.unimined.init.Io.name(cwd()),
        version = one("--mod-version") ?: "1.0.0",
        modid = one("--modid"),
        packageName = one("--package"),
        mcVersion = mc,
        loaders = loaders,
        mappings = flags["--mappings"].orEmpty(),
        dsl = if (one("--dsl") == "groovy") InitSpec.Dsl.GROOVY else InitSpec.Dsl.KOTLIN,
        pluginVersion = (if (template != null) "" else
            (one("--plugin-version") ?: if (one("--include-build") != null) ""
                else need("--plugin-version")))
            .let { if (it == "latest") resolver.latestPlugin() else it },
        gradleVersion = one("--gradle"),
        templateName = template,
        // --metadata dsl: generate metadata via `mod { }` instead of
        // static resource files (unimined-mod's emitters)
        metadataFromDsl = one("--metadata") == "dsl",
        layout = when {
            "--modules" in flags -> InitSpec.Layout.MULTILOADER
            one("--product") != null -> InitSpec.Layout.CUSTOM_PRODUCT
            else -> InitSpec.Layout.FLAT
        },
        product = one("--product"),
        distFile = one("--dist"),
        jarmod = "--jarmod" in flags,
        splitJars = one("--jars") != "merged",
        noWrapper = "--no-wrapper" in flags,
        includeBuild = one("--include-build"),
        toolchain = one("--toolchain")?.toInt(),
        extraLines = flags["--extra"].orEmpty(),
        side = one("--side")?.uppercase(),
        vars = flags["--var"].orEmpty().associate {
            val eq = it.indexOf('=')
            require(eq > 0) { "--var wants k=v, got '$it'" }
            it.substring(0, eq) to it.substring(eq + 1)
        },
        // local path or http(s) URI — remote zips stream through
        // the generator's downloader
        templatesZip = one("--templates")?.takeIf { Io.exists(it) },
        templatesUrl = one("--templates")?.takeUnless { Io.exists(it) },
    )

    val out = one("--out")
        ?: if (command == "new") name!! else "."
    val written = ProjectGenerator.generate(spec, out, overwrite = command == "init")
    println("generated ${written.size} files in ${Io.resolve(out)}")
}
