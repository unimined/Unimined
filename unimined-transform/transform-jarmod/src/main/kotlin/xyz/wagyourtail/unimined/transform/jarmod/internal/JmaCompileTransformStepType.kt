package xyz.wagyourtail.unimined.transform.jarmod.internal

import org.gradle.api.GradleException
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional
import xyz.wagyourtail.unimined.api.spec.RemapSpec
import xyz.wagyourtail.unimined.remap.spi.ArtifactStep
import xyz.wagyourtail.unimined.remap.spi.ModArtifactOnlyStepSpec
import xyz.wagyourtail.unimined.remap.spi.RemapStepType
import xyz.wagyourtail.unimined.remap.spi.RemapSurface
import xyz.wagyourtail.unimined.remap.spi.StepContext
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.reflect.KClass

/**
 * `jma-compiletime-transforms` — 1.x
 * `JarModAgentPatcher.compiletimeTransforms`: runs JarModAgent's
 * main-class ClassTransform pass over the mod jar in a forked JVM
 * before the namespace remap. [ModArtifactOnlyStepSpec] so the MC
 * dev-artifact pipeline drops it; contributed as a *pre-step* so the
 * transformers run on the original-namespace input (dep views never
 * consume pre-steps).
 */
abstract class JmaCompileTransformStepSpec : ModArtifactOnlyStepSpec {
    @get:Input
    @get:Optional
    override val engine: String?
        get() = null

    /** Absolute path of the jarmod-agent fat jar. */
    @get:Input
    abstract val agentJar: Property<String>

    /** Transformer class names (`jma.transformers`). */
    @get:Input
    abstract val transforms: ListProperty<String>

    /** Emit `-Djma.debug=true` (1.x always did for compile-time runs). */
    @get:Input
    abstract val debug: Property<Boolean>
}

class JmaCompileTransformStepType : RemapStepType<JmaCompileTransformStepSpec> {
    override val name = "unimined"
    override val stepName = "jma-compiletime-transforms"
    override val specType: KClass<JmaCompileTransformStepSpec> =
        JmaCompileTransformStepSpec::class
    override val surfaces = setOf(RemapSurface.JAR)
    override val needsClasspath = true
    override fun supports(spec: RemapSpec) = true

    override fun create(spec: JmaCompileTransformStepSpec, ctx: StepContext) =
        ArtifactStep { input, c ->
            val workDir = Files.createDirectories(
                c.workDir.resolve("jma-compiletime-transforms"))
            val output = workDir.resolve("jma-transformed.jar")
            if (!Files.exists(output)) {
                val staged = workDir.resolve("jma-input.jar")
                Files.copy(input, staged, StandardCopyOption.REPLACE_EXISTING)
                val isWindows = System.getProperty("os.name")
                    .lowercase().contains("win")
                val javaBin = Path.of(
                    System.getProperty("java.home"), "bin",
                    if (isWindows) "java.exe" else "java")
                val proc = ProcessBuilder(buildList {
                    add(javaBin.toString())
                    add("-Djma.transformers=" +
                            spec.transforms.get()
                                .joinToString(File.pathSeparator))
                    if (spec.debug.getOrElse(false)) add("-Djma.debug=true")
                    add("-cp")
                    add(spec.agentJar.get())
                    add("xyz.wagyourtail.unimined.jarmodagent.JarModAgent")
                    add(staged.toString())
                    add(c.classpath.joinToString(File.pathSeparator) {
                        it.toString()
                    })
                    add(output.toString())
                }).redirectErrorStream(true).start()
                val log = proc.inputStream.bufferedReader().readText()
                val exit = proc.waitFor()
                if (exit != 0) throw GradleException(
                    "jma-compiletime-transforms failed (exit $exit):\n$log")
            }
            output
        }
}
