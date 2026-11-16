package xyz.wagyourtail.unimined.adapter.patchbase.internal

import org.gradle.api.Project
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.jvm.tasks.Jar
import xyz.wagyourtail.unimined.adapter.patchbase.PatchBaseEnvExtension.PatchBaseCreatorSpec
import xyz.wagyourtail.unimined.api.util.registerDeprecatedTaskAlias
import xyz.wagyourtail.unimined.minecraft.MinecraftEnvironment
import java.io.File

/**
 * The `recompileMc` half of the patch loop — the FG-era
 * `recompMc`/`packagePatchedMc` analog.
 *
 * [PatchBaseCreatorSpec.sourceDir]'s patched overlay compiles against
 * the environment's dev-namespace Minecraft jar plus its libraries,
 * and the resulting classes merge *over* the dev jar: first copy wins
 * ([DuplicatesStrategy.EXCLUDE]), so recompiled classes replace the
 * stock entries while unpatched classes and resources carry through
 * untouched. The output is a full dev-namespace Minecraft jar — feed
 * it to another environment's `legacydist { file(...) }` (declare
 * `namespace` to the dev namespace it carries), or keep it as a local
 * patched install.
 *
 * Dev runs never need this task — the overlay already compiles into
 * the env's classes and wins on the priority classpath; `recompileMc`
 * exists for the packaged artifact. `makeStart` has no analog: run
 * configs launch the dev artifact + libraries directly.
 */
internal object RecompileMcWiring {

    /** Base-jar entries never carried into a recompiled jar — the
     *  stock manifest and signature blocks would break the merged
     *  classes (stale digests; Mojang ships signed jars pre-1.14,
     *  and jarsigner verification rejects a jar whose signed content
     *  changed). The `Jar` task writes a fresh default manifest. */
    val BASE_EXCLUDES = listOf(
        "META-INF/MANIFEST.MF",
        "META-INF/*.SF",
        "META-INF/*.DSA",
        "META-INF/*.RSA",
        "META-INF/*.EC",
        "META-INF/SIG-*",
    )

    /**
     * Registers `recompile<Env>McClasses` + `recompile<Env>Mc` for the
     * creator spec (`recompileMc[Classes]` for `main`; the
     * pre-convention `recompileMc<Env>[Classes]` names register
     * deprecated aliases). Reads the overlay tree as-is — deliberately
     * no task dependency on `apply<Env>SourcePatch`: the apply wipes
     * and repopulates the overlay on every run, so auto-chaining it
     * would drop uncommitted edits. Run `apply<Env>SourcePatch` first
     * to rebuild from `patches/`.
     */
    fun wire(
        project: Project,
        env: MinecraftEnvironment,
        spec: PatchBaseCreatorSpec,
        taskSfx: String,
        sourceDirFile: File,
    ) {
        val envId = env.environmentId
        val devView = project.configurations.named(env.minecraftDevViewName)
        val libraries = project.configurations.named(
            env.minecraftLibrariesConfigurationName)
        val classesDir = project.layout.buildDirectory
            .dir("unimined/recompileMc$taskSfx/classes")

        val compile = project.tasks.register(
            "recompile${taskSfx}McClasses", JavaCompile::class.java) {
            it.group = "patchbase"
            it.description = "Compiles the '$envId' patched source " +
                "overlay against the '$envId' dev Minecraft jar"
            // the overlay also carries verbatim resource patches —
            // javac only wants the .java half
            it.source = project.fileTree(spec.sourceDir) { t ->
                t.include("**/*.java")
            }
            it.destinationDirectory.set(classesDir)
            // dev jar + MC libraries — every type the patched sources
            // can reference; the dev artifact resolves lazily at
            // execution
            it.classpath = project.files(devView, libraries)
            it.options.encoding = "UTF-8"
            // the env's compile level — release only for >=9 (javac 8
            // rejects the flag); source/target resolve at execution
            // like the env's own compile wiring (pre-9 releases also
            // keep `_` identifiers legal for old decompiled sources)
            it.options.release.set(env.compileJavaVersion.map { l ->
                l.takeIf { v -> v > 8 }
            })
            val compileLevel = env.compileJavaVersion
            it.doFirst("unimined java compat") { t ->
                (t as JavaCompile).sourceCompatibility =
                    compileLevel.get().toString()
                (t as JavaCompile).targetCompatibility =
                    compileLevel.get().toString()
            }
        }

        val recompile = project.tasks.register(
            "recompile${taskSfx}Mc", Jar::class.java) {
            it.group = "patchbase"
            it.description = "Recompiles the '$envId' Minecraft jar " +
                "with the patch overlay merged in — the FG-era " +
                "recompMc analog (dev namespace)"
            // artifactBaseName already embeds the MC version —
            // suppress the project.version slot so the name stays
            // `minecraft-<env>-<mcver>-recompiled.jar`
            it.archiveBaseName.set(env.artifactBaseName)
            it.archiveVersion.set("")
            it.archiveClassifier.set("recompiled")
            it.dependsOn(compile)
            overlayCopy(
                it,
                classes = compile.flatMap { c -> c.destinationDirectory },
                overlay = spec.sourceDir,
                // devView resolves at execution — the provider→zipTree
                // keeps the env artifact lazy (CC-safe)
                baseTree = project.zipTree(
                    devView.map { conf -> conf.singleFile }),
            )
            it.onlyIf("patched overlay exists") { sourceDirFile.isDirectory }
        }
        // pre-convention env-suffix names — deprecated aliases
        if (envId != "main") {
            project.registerDeprecatedTaskAlias(
                "recompileMc${taskSfx}Classes", compile)
            project.registerDeprecatedTaskAlias(
                "recompileMc$taskSfx", recompile)
        }
    }

    /**
     * The overlay copy-spec — shared with the wiring test so the
     * merge semantics are exercised on real task instances: [classes]
     * (recompiled overlay) and [overlay] (verbatim resource patches)
     * precede [baseTree] (the dev jar's contents, signatures
     * stripped), so [DuplicatesStrategy.EXCLUDE] keeps the patched
     * entries and the base supplies everything else.
     */
    fun overlayCopy(
        task: Jar,
        classes: Any,
        overlay: Any,
        baseTree: Any,
    ) {
        task.from(classes)
        task.from(overlay) { it.exclude("**/*.java") }
        task.from(baseTree) { it.exclude(BASE_EXCLUDES) }
        task.duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    }

}
