package xyz.wagyourtail.unimined.remapper.jst

import org.gradle.api.GradleException
import xyz.wagyourtail.unimined.api.spec.RemapSpec
import xyz.wagyourtail.unimined.remap.spi.ArtifactStep
import xyz.wagyourtail.unimined.remap.spi.RemapStepType
import xyz.wagyourtail.unimined.remap.spi.RemapSurface
import xyz.wagyourtail.unimined.remap.spi.StepContext
import java.io.File
import kotlin.reflect.KClass

/**
 * `jst` step on the SOURCES surface — runs `net.neoforged.jst.cli.Main`
 * in a JVM subprocess. JST handles jar input/output natively
 * (`--input-format ARCHIVE --output-format ARCHIVE`), so unlike
 * `source-remap` no unpack/repack is needed. The step declares
 * [needsClasspath] — JST resolves symbols against the compile classpath.
 */
class JstStep : RemapStepType<JstStepSpec> {

    override val name = "jst"
    override val stepName = "jst"
    override val specType: KClass<JstStepSpec> = JstStepSpec::class
    override val surfaces: Set<RemapSurface> = setOf(RemapSurface.SOURCES)
    override val needsClasspath = true

    override fun supports(spec: RemapSpec) = true

    override fun create(spec: JstStepSpec, ctx: StepContext) = ArtifactStep { input, c ->
        val toolCp = spec.toolClasspath.files
        require(toolCp.isNotEmpty()) {
            "jst: toolClasspath is empty — wire a configuration " +
                    "resolving 'net.neoforged.jst:jst-cli'"
        }
        val out = c.workDir.resolve("jst-out.jar")
        val javaBin = File(System.getProperty("java.home"), "bin/java").absolutePath

        val args = buildList {
            add(javaBin)
            add("-cp")
            add(toolCp.joinToString(File.pathSeparator) { it.absolutePath })
            add("net.neoforged.jst.cli.Main")
            addAll(listOf("--input-format", "ARCHIVE", "--output-format", "ARCHIVE"))
            if (c.classpath.isNotEmpty()) {
                addAll(
                    listOf(
                        "--classpath",
                        c.classpath.joinToString(File.pathSeparator)
                    )
                )
            }
            spec.enable.get().forEach { addAll(listOf("--enable", it)) }
            addAll(spec.extraArgs.get())
            add(input.toString())
            add(out.toString())
        }
        c.log.subLogger("jst").info(args.joinToString(" "))

        val proc = ProcessBuilder(args).redirectErrorStream(true).start()
        val output = proc.inputStream.bufferedReader().readText()
        if (proc.waitFor() != 0) {
            throw GradleException("jst tool failed:\n$output")
        }
        c.logger.debug(output)
        out
    }

}
