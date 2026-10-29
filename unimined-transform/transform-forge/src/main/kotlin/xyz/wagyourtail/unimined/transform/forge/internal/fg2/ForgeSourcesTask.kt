package xyz.wagyourtail.unimined.transform.forge.internal.fg2

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import xyz.wagyourtail.commonskt.reader.CharReader
import xyz.wagyourtail.unimined.mapping.formats.at.ATReader
import xyz.wagyourtail.unimined.mapping.formats.at.ATWriter
import xyz.wagyourtail.unimined.transform.forge.internal.mcpconfig.McpConfigRunner
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarFile
import kotlin.io.path.*
import xyz.wagyourtail.unimined.transform.forge.internal.mcpconfig.GradleExecPort
import xyz.wagyourtail.unimined.transform.forge.internal.userdev.UserdevExtract
import xyz.wagyourtail.unimined.transform.forge.internal.userdev.UserdevSourcesChain
import xyz.wagyourtail.unimined.transform.forge.internal.UserdevConfig
import xyz.wagyourtail.unimined.transform.forge.internal.userdevAts

/**
 * Runs the userdev `mcp` step chain plus the FG3 sources splice-ins to
 * produce the dev sources jar (`resultFor("forgePatch")`).
 *
 * Resolution is wire-side, expressed as Gradle inputs so the task stays
 * configuration-cache safe: the mcp config zip, env artifacts,
 * libraries, AT files, and every function's tool classpath are
 * `FileCollection`/`Property` inputs; only execution happens in
 * [produce].
 */
abstract class ForgeSourcesTask : DefaultTask() {

    /** The resolved userdev jar — carries `ats`/`patches`/config. */
    @get:InputFile
    @get:Classpath
    abstract val userdevJar: RegularFileProperty

    /** The resolved `config.mcp` artifact zip. */
    @get:InputFile
    @get:Classpath
    abstract val mcpConfigZip: RegularFileProperty

    /** Vanilla client jar (`Env.clientJar`). */
    @get:InputFile
    @get:Classpath
    abstract val clientJar: RegularFileProperty

    /** Vanilla server jar — joined environments only. */
    @get:Optional
    @get:InputFile
    @get:Classpath
    abstract val serverJar: RegularFileProperty

    /** Official mappings channels (`Env.clientMappings`/`serverMappings`). */
    @get:Optional
    @get:InputFile
    @get:Classpath
    abstract val clientMappings: RegularFileProperty

    @get:Optional
    @get:InputFile
    @get:Classpath
    abstract val serverMappings: RegularFileProperty

    /** `minecraftLibraries` resolved jars (`Env.libraries`). */
    @get:InputFiles
    @get:Classpath
    abstract val libraries: ConfigurableFileCollection

    /** `*:sources` classifier jar — `forgeInject` contents. */
    @get:Optional
    @get:InputFile
    @get:Classpath
    abstract val sourcesJar: RegularFileProperty

    /** All AT files applied before decompile (userdev `ats` +
     *  environment `accessTransformers`). */
    @get:InputFiles
    @get:Classpath
    abstract val atFiles: ConfigurableFileCollection

    /** Resolved accesstransformers tool jars. */
    @get:InputFiles
    @get:Classpath
    abstract val atToolJars: ConfigurableFileCollection

    /** Function/tool coordinate → resolved classpath files — the
     *  `Env.toolClasspath` lookup table, resolved lazily wire-side. */
    @get:Input
    abstract val toolIndex: MapProperty<String, List<String>>

    /** `steps.<side>` selection — `client`/`server`/`joined`. */
    @get:Input
    abstract val side: Property<String>

    /** Whether tool execs resolve a Gradle java toolchain
     *  (1.x `useToolchains` — opt-out). */
    @get:Input
    abstract val useToolchains: Property<Boolean>

    /** The environment's effective java requirement — the tool
     *  `java_version` fallback for toolchain execs. */
    @get:Input
    @get:Optional
    abstract val mcJavaVersion: Property<Int>

    /** The userdev `config.json` text — reparsed in [produce] so the
     *  task input is the raw config, not Gradle-resolved objects. */
    @get:Input
    abstract val userdevConfigJson: Property<String>

    @get:OutputFile
    abstract val outputJar: RegularFileProperty

    @TaskAction
    fun produce() {
        val config = UserdevConfig(
            Json.parseToJsonElement(userdevConfigJson.get()).jsonObject
        )
        val workDir = temporaryDir.toPath().resolve("mcp").createDirectories()
        val userdev = userdevJar.get().asFile.toPath()
        val index = toolIndex.get()

        val env = McpConfigRunner.Env(
            clientJar = { clientJar.get().asFile.toPath() },
            serverJar = {
                serverJar.orNull?.asFile?.toPath()
                    ?: error("serverJar required for side=${side.get()}")
            },
            clientMappings = {
                clientMappings.orNull?.asFile?.toPath()
                    ?: error("clientMappings not provided")
            },
            serverMappings = {
                serverMappings.orNull?.asFile?.toPath()
                    ?: error("serverMappings not provided")
            },
            libraries = { libraries.files.map { it.toPath() } },
            toolClasspath = { coord ->
                index[coord]?.map { Path.of(it) }
                    ?: error("no resolved tool classpath for $coord")
            },
            exec = port()
        )

        val runner = McpConfigRunner(
            mcpConfigZip.get().asFile.toPath(), workDir, side.get(), env
        )

        // `*:sources` → forgeInject input dir
        val sourcesDir = workDir.resolve("sources").createDirectories()
        sourcesJar.orNull?.let { jar ->
            UserdevExtract.extractSources(jar.asFile.toPath(), sourcesDir)
        }

        // userdev `ats` + declared ATs, merged for the AT tool
        val userdevAts = config.ats.flatMap {
            UserdevExtract.extractPrefix(userdev, it, workDir.resolve("ats"))
        }
        val allAts = (atFiles.files.map { it.toPath() } + userdevAts)

        val atCp = atToolJars.files.map { it.toPath() }
        val applyAts: (Path, List<Path>, Path) -> Unit = { input, ats, output ->
            if (ats.isNotEmpty()) applyAccessTransformers(
                atCp, input, ats, output, workDir
            )
        }

        UserdevSourcesChain.install(
            runner, userdev, sourcesDir, allAts, applyAts,
            workDir.resolve("forge"), config
        )

        val out = runner.resultFor("forgePatch").output
            ?: error("forgePatch produced no output")
        outputJar.get().asFile.parentFile.mkdirs()
        Files.copy(
            out, outputJar.get().asFile.toPath(),
            java.nio.file.StandardCopyOption.REPLACE_EXISTING
        )
    }

    /**
     * 1.x `AccessTransformerMinecraftTransformer.transform` — merge all
     * AT files into one modern-format cfg, then exec the AT tool
     * (`TransformerProcessor --inJar --outJar --atFile`).
     */
    private fun applyAccessTransformers(
        atToolClasspath: List<Path>,
        input: Path, ats: List<Path>, output: Path, workDir: Path
    ) {
        if (output.exists()) output.deleteIfExists()
        output.parent.createDirectories()
        val merged = workDir.resolve("${input.fileName}-mergedATs.cfg")
        merged.deleteIfExists()
        val items = ats.flatMap { at ->
            ATReader.readData(CharReader(at.readText()))
        }
        merged.bufferedWriter().use { w ->
            ATWriter.writeData(items) { w.append(it) }
        }
        val mainClass = atToolClasspath.firstNotNullOfOrNull { jar ->
            runCatching {
                JarFile(jar.toFile()).use {
                    it.manifest.mainAttributes
                        .getValue(java.util.jar.Attributes.Name.MAIN_CLASS)
                }
            }.getOrNull()
        } ?: "net.minecraftforge.accesstransformer.TransformerProcessor"
        port().exec(
            mainClass, atToolClasspath, listOf(
                "--inJar", input.absolutePathString(),
                "--outJar", output.absolutePathString(),
                "--atFile", merged.absolutePathString()
            ), emptyList(), null
        )
    }

    /** A toolchain-aware exec port for this task's runs. */
    private fun port(): GradleExecPort =
        project.objects.newInstance(GradleExecPort::class.java).apply {
            useToolchains = { this@ForgeSourcesTask.useToolchains.get() }
            defaultJavaVersion = { mcJavaVersion.get() }
        }
}
