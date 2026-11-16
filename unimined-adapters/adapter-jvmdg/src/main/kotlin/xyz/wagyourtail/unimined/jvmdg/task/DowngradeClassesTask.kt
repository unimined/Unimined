package xyz.wagyourtail.unimined.jvmdg.task

import org.gradle.api.DefaultTask
import xyz.wagyourtail.unimined.api.util.failProblem
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.gradle.work.DisableCachingByDefault
import xyz.wagyourtail.jvmdg.ClassDowngrader
import xyz.wagyourtail.jvmdg.compile.PathDowngrader
import xyz.wagyourtail.jvmdg.compile.ZipDowngrader
import xyz.wagyourtail.jvmdg.gradle.flags.ShadeFlags
import xyz.wagyourtail.jvmdg.gradle.flags.toFlags
import xyz.wagyourtail.unimined.jvmdg.JvmDgEnvSpec
import xyz.wagyourtail.unimined.jvmdg.internal.JvmdgEnvWiring

/**
 * Downgrades every directory in [inputDirs] to [ShadeFlags.downgradeTo]
 * via JvmDowngrader, mirroring each input dir to `outputDir/<index>` so
 * classpath order is preserved. Registered per-environment by the
 * `unimined-jvmdg` compat plugin when the env's `jvmdowngrader`
 * extension is enabled — old-loader runtimes (FG-era LaunchWrapper/FML
 * ship ASM 4/5.2 and cannot read >v52 classes) launch against the
 * downgraded dirs instead of the raw compile outputs.
 *
 * The task implements jvmdg's Gradle-frontend [ShadeFlags] — its
 * property inputs carry the interface's `@Input` annotations and are
 * conventioned wholesale from the env's [JvmDgEnvSpec] (see
 * `convention(spec)` in [JvmdgEnvWiring]), so every engine knob the
 * user sets on `jvmdowngrader { }` reaches [toFlags] at execution.
 */
@DisableCachingByDefault(because = "jvmdg engine output isn't relocation-verified; up-to-date checks cover the rerun cost")
abstract class DowngradeClassesTask : DefaultTask(), ShadeFlags {

    /** Compiled class dirs to downgrade (`sourceSet.output.classesDirs`). */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val inputDirs: ConfigurableFileCollection

    /** Whether downgrading is active for this env — [JvmDgEnvSpec.enabled].
     *  An input rather than an onlyIf spec so the configuration cache
     *  stores the resolved value (spec/provider captures can't
     *  serialize). Named `downgradeEnabled` because `enabled` collides
     *  with `Task.getEnabled`. */
    @get:Input
    abstract val downgradeEnabled: Property<Boolean>

    /** The env's run JVM floor — the bound downgraded classes must
     *  stay readable by.
     *  [MinecraftEnvironment.requiredJavaVersion], as a task input so
     *  the resolved int is what serializes. */
    @get:Input
    abstract val requiredJava: Property<Int>

    /** The level the env's classes were actually compiled at —
     *  [MinecraftEnvironment.compileJavaVersion], which a
     *  `jvmdowngrader.source` may raise above [requiredJava]. The
     *  gate that makes this a passthrough when the compile already
     *  emits at/below the era target. */
    @get:Input
    abstract val compileJava: Property<Int>

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
        val flags = toFlags()
        val target = flags.classVersion - 44
        // fail fast: downgraded classes are still bounded by the
        // launcher JVM — a target above the env's java floor produces
        // classes the runtime can't read
        if (target > requiredJava.get()) project.failProblem(
            "jvmdg/java-floor",
            "unimined: ${path.removePrefix(":").removeSuffix("Classes")}: " +
                    "jvmdowngrader.target=$target emits class files for " +
                    "Java $target but the run JVM floor is " +
                    "${requiredJava.get()}",
            "raise `javaVersion` to at least $target or lower " +
                    "jvmdowngrader.target"
        )
        // compiling at/below target makes this a passthrough —
        // compileJava (not requiredJava) is what javac emitted: a
        // `jvmdowngrader.source` may raise it above the run floor
        if (compileJava.get() <= target) return
        val inputs = inputDirs.files.filter { it.isDirectory }.map { it.toPath() }
        if (inputs.isEmpty()) return
        val base = outputDir.get().asFile.toPath()
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
            apiJar.get().forEachIndexed { i, apiJar ->
                ZipDowngrader.downgradeZip(
                    downgrader, apiJar.toPath(), emptySet(),
                    base.resolve("api-$i.jar")
                )
            }
        }
    }

}
