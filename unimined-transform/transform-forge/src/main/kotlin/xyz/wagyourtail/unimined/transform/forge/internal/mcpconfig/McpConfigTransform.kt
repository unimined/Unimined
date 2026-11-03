package xyz.wagyourtail.unimined.transform.forge.internal.mcpconfig

import org.gradle.api.artifacts.transform.InputArtifact
import org.gradle.api.artifacts.transform.TransformAction
import org.gradle.api.artifacts.transform.TransformOutputs
import org.gradle.api.artifacts.transform.TransformParameters
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
        /** Union of every `functions.*` tool's resolved classpath. */
        @get:Classpath val toolJars: ConfigurableFileCollection
        @get:Input val side: Property<String>
        @get:Input val notchObf: Property<Boolean>
        @get:Input val useToolchains: Property<Boolean>
        @get:Input val requiredJavaVersion: Property<Int>
        /** javaVersion → executable path, resolved lazily at
         *  fingerprint (JavaToolchainService isn't injectable into
         *  transform actions). */
        @get:Input val javaBinaries: MapProperty<String, String>
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
        // content-keyed by descriptor filename + side — global cache
        // so exists() substep skips work across invocations
        val cacheDir = params.cacheRoot.get().asFile.toPath().resolve(
            mcpZip.nameWithoutExtension
                .replace(Regex("[^A-Za-z0-9._-]+"), "-")
        ).resolve(side)

        val useToolchains = params.useToolchains.get()
        val javaBinaries = params.javaBinaries.get()
        val fallbackJava =
            File(System.getProperty("java.home"), "bin/java").absolutePath
        val toolJars = params.toolJars.files.toList()
        val pipeline = McpConfigDescriptorFormat().parse(
            mcpZip,
            object : FormatContext {
                override val side = side
                override val bindingNames = setOf(
                    "client", "server", "clientMappings", "serverMappings"
                )
            }
        )
        val step = if (params.notchObf.get() && side == "joined")
            "merge" else "rename"
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
            // per-tool isolated classpath: the union pollutes
            // serialization (e.g. asm-6.1-beta2 shadowing
            // mergetool's bundled 6.0 produces non-canonical
            // merge output). mcpconfig tools are fat/shaded and
            // self-contained, so filter by artifact name; fall
            // back to the union if nothing matches (thin tools).
            toolResolver = { coord ->
                val artifact = coord.substringAfter(':')
                    .substringBefore(':')
                val own = toolJars.filter {
                    it.name.startsWith("$artifact-") ||
                            it.name == "$artifact.jar"
                }
                (own.ifEmpty { toolJars }).map { it.toPath() }
            },
            listBindings = mapOf(
                "libraries" to { params.libraries.files.toList() }
            ),
            toolExec = { tool, args, jvmArgs, classpath ->
                val java = if (useToolchains) javaBinaries[
                    (tool.javaVersion ?: params.requiredJavaVersion.orNull)
                        ?.toString()
                ] ?: fallbackJava else fallbackJava
                execOps.exec { spec ->
                    spec.executable = java
                    spec.args(
                        jvmArgs + "-cp" +
                                classpath.joinToString(File.pathSeparator) +
                                tool.mainClass + args
                    )
                }.assertNormalExitValue()
            }
        ).execute(mcpZip, side, null, cacheDir, untilStep = step)
            ?: error("mcpconfig subpipeline produced no '$step' output")

        outputs.file("cleanJar.jar").let { out ->
            output.toFile().copyTo(out, overwrite = true)
        }
    }
}
