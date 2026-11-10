package xyz.wagyourtail.unimined.jvmdg

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.gradle.work.DisableCachingByDefault
import xyz.wagyourtail.jvmdg.ClassDowngrader
import xyz.wagyourtail.jvmdg.cli.Flags
import xyz.wagyourtail.jvmdg.compile.PathDowngrader
import xyz.wagyourtail.jvmdg.compile.ZipDowngrader

/**
 * Downgrades every directory in [inputDirs] to [classVersion] via
 * JvmDowngrader, mirroring each input dir to `outputDir/<index>` so
 * classpath order is preserved. Registered per-environment by the
 * `unimined-jvmdg` compat plugin when the env's `jvmdowngrader`
 * extension is enabled — old-loader runtimes (FG-era LaunchWrapper/FML
 * ship ASM 4/5.2 and cannot read >v52 classes) launch against the
 * downgraded dirs instead of the raw compile outputs.
 */
@DisableCachingByDefault(because = "jvmdg engine output isn't relocation-verified; up-to-date checks cover the rerun cost")
abstract class DowngradeClassesTask : DefaultTask() {

    /** Compiled class dirs to downgrade (`sourceSet.output.classesDirs`). */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val inputDirs: ConfigurableFileCollection

    /** Class-file major version to emit (`52` = Java 8). */
    @get:Input
    abstract val classVersion: Property<Int>

    /** Whether downgrading is active for this env — [JvmDgEnvSpec.enabled].
     *  An input rather than an onlyIf spec so the configuration cache
     *  stores the resolved value (spec/provider captures can't
     *  serialize). Named `downgradeEnabled` because `enabled` collides
     *  with `Task.getEnabled`. */
    @get:Input
    abstract val downgradeEnabled: Property<Boolean>

    /** The env's compile java floor — the gate that makes this a
     *  passthrough when the compile emits at/below the era target.
     *  [MinecraftEnvironment.requiredJavaVersion], as a task input so
     *  the resolved int is what serializes. */
    @get:Input
    abstract val requiredJava: Property<Int>

    /** `jvmdowngrader-java-api` jar(s) — the stub surface calls are
     *  backported onto. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val apiJars: ConfigurableFileCollection

    /** Resolution classpath for supertype/stub lookup (the env's
     *  runtime classpath plus the input dirs themselves). */
    @get:Classpath
    abstract val classpath: ConfigurableFileCollection

    /** Root of the mirrored downgraded dirs — each input dir lands at
     *  `outputDir/<index>` preserving classpath order. */
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun downgrade() {
        // was onlyIf/doFirst-on-the-run-task — in-action so nothing
        // env/spec-shaped is captured by a spec or action lambda
        // under the configuration cache
        if (!downgradeEnabled.get()) return
        val target = classVersion.get() - 44
        // fail fast: downgraded classes are still bounded by the
        // launcher JVM — a target above the env's java floor produces
        // classes the runtime can't read
        if (target > requiredJava.get()) throw GradleException(
            "${path.removePrefix(":").removeSuffix("Classes")}: " +
                    "jvmdowngrader.target=$target emits class files for " +
                    "Java $target but the run JVM floor is " +
                    "${requiredJava.get()} — raise `javaVersion` to at " +
                    "least $target or lower jvmdowngrader.target"
        )
        // compiling at/below target makes this a passthrough
        if (requiredJava.get() <= target) return
        val inputs = inputDirs.files.filter { it.isDirectory }.map { it.toPath() }
        if (inputs.isEmpty()) return
        val base = outputDir.get().asFile.toPath()
        val flags = Flags().apply {
            classVersion = this@DowngradeClassesTask.classVersion.get()
            val api = apiJars.files.toList()
            if (api.isNotEmpty()) this.api = api
        }
        ClassDowngrader.downgradeTo(flags).use { downgrader ->
            PathDowngrader.downgradePaths(
                downgrader,
                inputs,
                inputs.mapIndexed { i, _ -> base.resolve(i.toString()) },
                (classpath.files + inputDirs.files).map { it.toURI().toURL() }.toSet()
            )
            // the run classpath needs the DOWNGRADED api jar, not the raw
            // one — the published jar carries stubs compiled at their own
            // JDK's class version (up to v64+), which era JVMs can't load
            // and era ASM can't scan (FML's JarDiscoverer throws). Writing
            // api-<i>.jar keeps every stub at the task's target.
            apiJars.files.forEachIndexed { i, apiJar ->
                ZipDowngrader.downgradeZip(
                    downgrader, apiJar.toPath(), emptySet(),
                    base.resolve("api-$i.jar")
                )
            }
        }
    }

}
