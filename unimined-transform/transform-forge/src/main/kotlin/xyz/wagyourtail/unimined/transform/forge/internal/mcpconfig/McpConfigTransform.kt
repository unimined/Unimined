package xyz.wagyourtail.unimined.transform.forge.internal.mcpconfig

import org.gradle.api.artifacts.transform.InputArtifact
import org.gradle.api.artifacts.transform.TransformAction
import org.gradle.api.artifacts.transform.TransformOutputs
import org.gradle.api.artifacts.transform.TransformParameters
import org.gradle.api.JavaVersion
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemLocation
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.process.ExecOperations
import xyz.wagyourtail.unimined.transform.step.subpipeline.FormatContext
import xyz.wagyourtail.unimined.transform.step.subpipeline.SubpipelineExecutor
import java.io.File
import javax.inject.Inject

/**
 * MCPConfig `config.json` → clean jar as a real artifact transform:
 * the userdev/neoform descriptor zip in, the `rename` (or notchObf
 * `merge`) step's output out. Registered per environment with a
 * distinct `to` artifact type — transform selection is attribute-only,
 * so per-env params require per-env types.
 *
 * Execution-time replacement for the old provider-eval
 * `mcpconfigCleanJar`: tools exec here, at transform time, inside the
 * global transform cache — never during configuration/CC-store.
 * [Params.cacheRoot] stays the global mcpconfig cache so the
 * executor's canonical-output `exists()` substep cache still gives
 * cross-invocation incrementality when only some inputs change.
 */
@org.gradle.api.artifacts.transform.CacheableTransform
abstract class McpConfigTransform @Inject constructor(
    private val execOps: ExecOperations,
) : TransformAction<McpConfigTransform.Params> {

    interface Params : TransformParameters {
        @get:InputFile @get:Classpath @get:Optional val clientJar: RegularFileProperty
        @get:InputFile @get:Classpath @get:Optional val serverJar: RegularFileProperty
        @get:InputFile @get:Classpath @get:Optional val clientMappings: RegularFileProperty
        @get:InputFile @get:Classpath @get:Optional val serverMappings: RegularFileProperty
        /** MC env classpath (`listLibraries` `-e=` rows). */
        @get:Classpath val libraries: ConfigurableFileCollection
        /** Union of every `functions.*` tool's resolved classpath —
         *  carried for the transform content fingerprint. */
        @get:Classpath val toolJars: ConfigurableFileCollection
        /** `functions.*` coord → pathSeparator-joined resolved
         *  classpath — the per-tool exec classpath. A union keyed on
         *  jar-name can't attribute shared libs (jopt-simple) to the
         *  thin tools that actually need them (DiffPatch). */
        @get:Input val toolJarPaths: MapProperty<String, String>
        @get:Input val side: Property<String>
        @get:Input val notchObf: Property<Boolean>
        @get:Input val useToolchains: Property<Boolean>
        @get:Input val requiredJavaVersion: Property<Int>
        /** javaVersion → executable path, resolved lazily at
         *  fingerprint (JavaToolchainService isn't injectable into
         *  transform actions). */
        @get:Input val javaBinaries: MapProperty<String, String>
        /** Installer-chain `rename` tool coordinate — split-side
         *  userdev binpatch packs (`data/<side>.lzma`) are generated
         *  against the *installer* pipeline's renamed jar
         *  (bundler-extract → jarsplitter → `ForgeAutoRenamingTool`),
         *  not the descriptor-declared `rename` tool (e.g. 1.20.1's
         *  packs checksum FART 0.1.22 output while `functions.rename`
         *  declares 1.0.2 — byte-divergent for identical input).
         *  When set, replaces the `rename` function's coords and
         *  salts the side cache dir. */
        @get:Input @get:Optional val renameToolOverride: Property<String>
        /** Global mcpconfig cache root — intermediates land under
         *  `<cacheRoot>/<side>/` for substep caching; does not affect
         *  output content. */
        @get:Internal val cacheRoot: DirectoryProperty
    }

    @get:InputArtifact @get:Classpath
    protected abstract val inputArtifact: Provider<FileSystemLocation>

    override fun transform(outputs: TransformOutputs) {
        val params = parameters
        val mcpZip = inputArtifact.get().asFile
        val side = params.side.get()
        // content-keyed by descriptor bytes (+ side) — global cache so
        // exists() substep skips work across invocations; a stale or
        // misnamed descriptor can never hit another artifact's outputs.
        // The rename-tool override salts the side dir — the `exists()`
        // substep gate is content-blind, so outputs produced under the
        // descriptor-declared tool would otherwise be served when the
        // effective tool changes.
        val renameOverride = params.renameToolOverride.orNull
        val cacheDir = params.cacheRoot.get().asFile.toPath()
            .resolve(mcpConfigCacheKey(mcpZip))
            .resolve(sideCacheKey(side, renameOverride))

        val useToolchains = params.useToolchains.get()
        val javaBinaries = params.javaBinaries.get()
        val fallbackJava =
            File(System.getProperty("java.home"), "bin/java").absolutePath
        val toolJars = params.toolJars.files.toList()
        var pipeline = McpConfigDescriptorFormat().parse(
            mcpZip,
            object : FormatContext {
                override val side = side
                override val bindingNames = setOf(
                    "client", "server", "clientMappings", "serverMappings"
                )
            }
        )
        if (renameOverride != null) {
            pipeline = pipeline.withToolOverride("rename", renameOverride)
        }
        // the clean jar is `decompile`'s input — the step that
        // produced it is the one to run through. Pipelines vary: srg-era
        // descriptors decompile `{renameOutput}`, unobf'd (26.x)
        // descriptors have no `rename` at all and decompile
        // `{mergeOutput}`/`{stripOutput}`. `untilStep` falls through to
        // the chain tail when the name is absent — a missing `rename`
        // silently yields the `patch` sources jar, so the step name
        // must be read off the descriptor, not guessed.
        val decompileInput = pipeline.steps
            .firstOrNull { it.name == "decompile" }
            ?.args?.get("input")
            ?.let {
                Regex("\\{(.+)Output\\}").find(it)?.groupValues?.get(1)
            }
        val step = if (params.notchObf.get()) {
            // notchObf binpatches index the *pre-rename* jar — the
            // obfNamespace boundary (1.x `executeMcp("merge")` for
            // joined). Derive it from `rename`'s input rather than
            // hardcoding `merge`: joined reads `{mergeOutput}`, split
            // sides read the strip output.
            pipeline.steps.firstOrNull { it.name == "rename" }
                ?.args?.get("input")
                ?.let {
                    Regex("\\{(.+)Output\\}").find(it)?.groupValues?.get(1)
                } ?: if (side == "joined") "merge" else "rename"
        } else decompileInput ?: "rename"
        val output = SubpipelineExecutor(
            pipeline,
            McpConfigDescriptorFormat(),
            McpConfigFunctionSource().functions().associateBy { it.name },
            bindings = listOfNotNull(
                params.clientJar.orNull?.asFile?.let { "client" to { it } },
                params.serverJar.orNull?.asFile?.let { "server" to { it } },
                params.clientMappings.orNull?.asFile
                    ?.let { "clientMappings" to { it } },
                params.serverMappings.orNull?.asFile
                    ?.let { "serverMappings" to { it } },
            ).toMap(),
            // per-tool isolated classpath: each coord resolves to its
            // own transitive set in toolJarPaths — the union pollutes
            // serialization (e.g. asm-6.1-beta2 shadowing mergetool's
            // bundled 6.0 produces non-canonical merge output) and
            // name-filtering drops thin tools' deps (DiffPatch needs
            // jopt-simple). Fall back to the union if the coord is
            // absent (defensive — map should cover every functions.*
            // entry the wiring parsed).
            toolResolver = { coord ->
                params.toolJarPaths.get()[coord]
                    ?.split(File.pathSeparator)
                    ?.map { java.nio.file.Path.of(it) }
                    ?: toolJars.map { it.toPath() }
            },
            listBindings = mapOf(
                "libraries" to { params.libraries.files.toList() }
            ),
            toolExec = { tool, args, jvmArgs, classpath ->
                val required = tool.javaVersion
                    ?: params.requiredJavaVersion.orNull
                val java = if (useToolchains) javaBinaries[
                    required?.toString()
                ] ?: fallbackJava else {
                    // 1.x parity — toolchains off means the build JVM
                    // must satisfy the tool's java requirement
                    if (required != null && JavaVersion.current() <
                        JavaVersion.toVersion(required)
                    ) {
                        error(
                            "tool ${tool.mainClass} requires java " +
                                    "$required but toolchains are disabled " +
                                    "and the build JVM is ${JavaVersion.current()}"
                        )
                    }
                    fallbackJava
                }
                val t0 = System.nanoTime()
                execOps.exec { spec ->
                    spec.executable = java
                    spec.args(
                        jvmArgs + "-cp" +
                                classpath.joinToString(File.pathSeparator) +
                                tool.mainClass + args
                    )
                }.assertNormalExitValue()
                xyz.wagyourtail.unimined.api.util.Perf.emit(
                    "tool", "main" to tool.mainClass,
                    "ms" to (System.nanoTime() - t0) / 1_000_000)
            }
        ).execute(mcpZip, side, null, cacheDir, untilStep = step)
            ?: error("mcpconfig subpipeline produced no '$step' output")

        outputs.file("cleanJar.jar").let { out ->
            output.toFile().copyTo(out, overwrite = true)
        }
    }
}

/**
 * Subpipeline work-dir key — `<sanitized-filename>-<sha256-16>` of
 * the descriptor zip's bytes. The filename stays as a readability prefix,
 * but identity is content: the executor's `exists()` substep cache
 * must never serve outputs produced from a different descriptor that
 * happened to share a filename (and identical content under a
 * different name still reuses the same substeps).
 */
internal fun mcpConfigCacheKey(zip: File): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    zip.inputStream().use { input ->
        val buf = ByteArray(8192)
        while (true) {
            val read = input.read(buf)
            if (read < 0) break
            digest.update(buf, 0, read)
        }
    }
    val name = zip.nameWithoutExtension.replace(Regex("[^A-Za-z0-9._-]+"), "-")
    return "$name-" + digest.digest()
        .joinToString("") { "%02x".format(it) }.take(16)
}

/**
 * The `<cacheRoot>/<key>/` subdir for one side's substep outputs —
 * salted by the effective [renameToolOverride] coordinate when set.
 * The executor's `exists()` substep gate is content-blind: a
 * `rename/renameOutput.jar` produced under the descriptor-declared
 * tool must never be served when the installer-chain tool replaces
 * it (and vice versa) — the coord digest keeps both variants
 * addressable without orphaning the plain-side dirs.
 */
internal fun sideCacheKey(side: String, renameToolOverride: String?): String =
    if (renameToolOverride == null) side
    else "$side-" + java.security.MessageDigest.getInstance("SHA-256")
        .digest(renameToolOverride.toByteArray())
        .joinToString("") { "%02x".format(it) }.take(8)

/**
 * Swap one `functions.*` tool's coord list — e.g. the
 * installer-chain `ForgeAutoRenamingTool` that split-side binpatch
 * packs checksum against, substituted for the descriptor-declared
 * `rename` tool. No-op when the function is absent (non-mcpconfig
 * descriptor shapes).
 */
internal fun xyz.wagyourtail.unimined.transform.step.subpipeline.DeclaredSubpipeline.withToolOverride(
    function: String, coord: String
): xyz.wagyourtail.unimined.transform.step.subpipeline.DeclaredSubpipeline {
    val tool = functions[function] ?: return this
    return copy(functions = functions + (function to tool.copy(coords = listOf(coord))))
}
