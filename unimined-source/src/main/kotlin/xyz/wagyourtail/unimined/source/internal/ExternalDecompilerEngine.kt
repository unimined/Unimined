package xyz.wagyourtail.unimined.source.internal

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.work.DisableCachingByDefault
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.TaskProvider
import org.gradle.process.ExecOperations
import xyz.wagyourtail.unimined.source.spi.DecompileSpec
import xyz.wagyourtail.unimined.source.spi.DecompilerEngine
import xyz.wagyourtail.unimined.source.spi.ModSourcesTransformSpec
import xyz.wagyourtail.unimined.source.task.DecompileTask
import javax.inject.Inject

/**
 * The `external` engine — wraps an arbitrary fernflower-CLI decompiler
 * coordinate (1.x `generator(dep)` parity). Selected via
 * `source { engine.set("external"); dependency.set("g:a:v") }` or the
 * `generator(dep)` verb; `mainClass` may be overridden through
 * `options["mainClass"]` (default `ConsoleDecompiler`).
 */
class ExternalDecompilerEngine : DecompilerEngine {

    override val name = "external"

    // low priority: only selected via `engine.set("external")` — the
    // dependency could be set while a real engine is still preferred
    override val priority: Int = 0

    override fun supports(spec: DecompileSpec): Boolean =
        spec.dependency != null

    override fun createTask(
        project: Project,
        name: String,
        spec: DecompileSpec
    ): TaskProvider<out DecompileTask> {
        val notation = spec.dependency ?: throw GradleException(
            "source { engine='external' }: set the decompiler coordinate " +
            "via dependency.set(\"g:a:v\") or generator(dep)"
        )
        val conf = project.configurations.detachedConfiguration(
            project.dependencies.create(notation)
        )
        return project.tasks.register(name, ExternalDecompileTask::class.java) { t ->
            t.inputJar.set(spec.input)
            t.classpath.from(spec.classpath)
            t.options.set(spec.options - "mainClass")
            t.jvmArgs.set(spec.jvmArgs)
            t.args.set(spec.args)
            t.mainClass.set(
                spec.options["mainClass"]
                    ?: "org.jetbrains.java.decompiler.main.decompiler.ConsoleDecompiler"
            )
            spec.javaVersion?.takeIf { it.isNotBlank() }?.let { v ->
                t.javaVersion.set(v)
                t.javaLauncher.set(
                    project.extensions.getByType(
                        org.gradle.jvm.toolchain.JavaToolchainService::class.java
                    ).launcherFor {
                        it.languageVersion.set(
                            org.gradle.jvm.toolchain.JavaLanguageVersion.of(v))
                    }.map { it.executablePath }
                )
            }
            t.decompilerClasspath.from(conf)
        }
    }

    override fun registerModSourcesTransform(
        project: Project,
        spec: ModSourcesTransformSpec
    ) {
        throw GradleException(
            "source { engine='external' }: mod-sources transforms are " +
            "not supported by the external engine — select a packaged " +
            "engine for genModSources"
        )
    }
}

/** Decompiles [inputJar] via a coordinate-supplied fernflower-CLI jar
 *  in a forked JVM. */
@DisableCachingByDefault
abstract class ExternalDecompileTask : DecompileTask() {

    /** The resolved decompiler classpath. */
    @get:InputFiles
    @get:Classpath
    abstract val decompilerClasspath: ConfigurableFileCollection

    /** The decompiler main class. */
    @get:Input
    abstract val mainClass: org.gradle.api.provider.Property<String>

    /** Toolchain-resolved `java` launcher — set by the engine when
     *  [javaVersion] is requested. */
    @get:Internal
    abstract val javaLauncher: RegularFileProperty

    @get:Inject
    abstract val execOperations: ExecOperations

    @TaskAction
    fun decompile() {
        val output = outputJar.get().asFile
        output.parentFile.mkdirs()
        execOperations.javaexec { spec ->
            spec.classpath = decompilerClasspath
            spec.mainClass.set(mainClass.get())
            javaLauncher.orNull?.let {
                spec.executable = it.asFile.absolutePath
            }
            spec.jvmArgs(jvmArgs.get())
            spec.args = options.get().map { (key, value) -> "-$key=$value" } +
                    args.get() +
                    listOf(
                        "-e=" + classpath.files.joinToString(
                            java.io.File.pathSeparator) { it.absolutePath },
                        inputJar.get().asFile.absolutePath,
                        "--file",
                        output.absolutePath
                    )
        }
    }
}
