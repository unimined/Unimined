package xyz.wagyourtail.unimined.cli

import xyz.wagyourtail.unimined.init.InitSpec
import xyz.wagyourtail.unimined.init.ProjectGenerator
import xyz.wagyourtail.unimined.init.VersionResolver
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.system.exitProcess

private const val USAGE = """
unimined — scaffold a new mod project

usage:
  unimined new <name> [options]     generate into ./<name>
  unimined init [options]           generate into the current directory

options:
  --group <g>            Maven group (required)
  --mc <ver>             Minecraft version (required)
  --plugin-version <v>   unimined plugin version (required)
  --loader <l=v>         loader, repeatable or comma-separated:
                         --loader fabric=0.16.0 --loader forge=47.2.0
  --mappings <dsl>       mappings DSL line, repeatable: --mappings "yarn(10)"
  --modid <id>           mod id (derived from name when omitted)
  --package <pkg>        base package (group.modid default)
  --mod-version <v>      mod version (1.0.0 default)
  --dsl <kotlin|groovy>  build-script DSL (kotlin default)
  --gradle <ver>         Gradle wrapper version (8.10.2 default)
  --out <dir>            output directory (overrides the subcommand default)

`latest` resolves against real manifests (piston-meta, fabric-meta,
loader maven-metadata.xml, wagyourtail maven) — pin explicitly to
skip resolution.

--template <name> --templates <zip> extracts testing/<name>/ from a
unimined-templates.zip instead of the minimal emit (scaffold flags
unused).
"""

fun main(args: Array<String>) {
    if (args.isEmpty()) {
        print(USAGE); return
    }
    try {
        run(args)
    } catch (e: IllegalArgumentException) {
        System.err.println("error: ${e.message}")
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
        cacheDir = Paths.get(
            System.getProperty("user.home"), ".unimined", "cache"
        )
    )
    val mc = (if (template != null) "" else need("--mc"))
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
        name = name ?: Paths.get("").toAbsolutePath().fileName.toString(),
        version = one("--mod-version") ?: "1.0.0",
        modid = one("--modid"),
        packageName = one("--package"),
        mcVersion = mc,
        loaders = loaders,
        mappings = flags["--mappings"].orEmpty(),
        dsl = if (one("--dsl") == "groovy") InitSpec.Dsl.GROOVY else InitSpec.Dsl.KOTLIN,
        pluginVersion = (if (template != null) "" else need("--plugin-version"))
            .let { if (it == "latest") resolver.latestPlugin() else it },
        gradleVersion = one("--gradle"),
        templateName = template,
        templatesZip = one("--templates")?.let { Paths.get(it) },
    )

    val out = one("--out")?.let { Paths.get(it) }
        ?: if (command == "new") Paths.get(name!!) else Paths.get("")
    val written = ProjectGenerator.generate(spec, out, overwrite = command == "init")
    println("generated ${written.size} files in ${out.toAbsolutePath().normalize()}")
}
