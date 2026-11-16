package xyz.wagyourtail.unimined.jvmdg.internal

import org.gradle.api.Action
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.file.Directory
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskProvider
import xyz.wagyourtail.jvmdg.gradle.flags.convention
import xyz.wagyourtail.unimined.api.util.registerDeprecatedTaskAlias
import xyz.wagyourtail.unimined.api.util.uniminedTools
import xyz.wagyourtail.unimined.minecraft.MinecraftEnvironment
import xyz.wagyourtail.unimined.minecraft.MinecraftExtension
import xyz.wagyourtail.unimined.remap.RemapExtension
import xyz.wagyourtail.unimined.remap.spi.DeferredStepContribution
import xyz.wagyourtail.unimined.remap.spi.RemapSurface
import xyz.wagyourtail.unimined.remap.spi.StepScope
import xyz.wagyourtail.unimined.runs.task.MinecraftRunTask
import xyz.wagyourtail.unimined.runs.RunsExtension
import xyz.wagyourtail.unimined.transform.TransformExtension
import xyz.wagyourtail.unimined.jvmdg.JvmDgEnvSpec
import xyz.wagyourtail.unimined.jvmdg.JvmDowngradeEnvJarStepSpec
import xyz.wagyourtail.unimined.jvmdg.task.DowngradeClassesTask
import java.io.File

/**
 * Per-environment wiring between Unimined's run-task seams and
 * JvmDowngrader. Registered by [GradlePlugin] for every env — all
 * behavior is gated on the env's `jvmdowngrader` extension
 * ([JvmDgEnvSpec.enabled]) so an applied-but-unconfigured plugin is
 * inert, and everything downstream resolves lazily because the DSL
 * may set `jvmdowngrader` after this wiring runs.
 */
internal object JvmdgEnvWiring {

    fun wire(
        project: Project,
        environment: MinecraftEnvironment,
        transform: TransformExtension,
        runs: RunsExtension,
        remap: RemapExtension,
        global: JvmDgEnvSpec
    ) {
        val environmentId = environment.name
        val spec = environment.extensions.create(
            "jvmdowngrader", JvmDgEnvSpec::class.java)

        // global fallback: the whole engine flag surface
        // (quiet/ignoreWarningsIn/multiReleaseVersions/…) conventions
        // onto `minecraft { jvmdowngrader {} }`'s spec in one call —
        // env-level sets always win. The composed conventions below
        // then layer enabled/source/target/apiJar/downgradeTo as
        // own env > inherited envs > global > built-in default
        spec.convention(global)

        // inheritance: a child env's spec conventions chain to its
        // inherited environments' specs (`sourceSet("a") {
        // jvmdowngrader(8); sourceSet("b") {} }` — b downgrades too),
        // explicit child settings always win. Off by default — the
        // plugin's presence is not the opt-in; `jvmdowngrader { }` /
        // `jvmdowngrader(N)` / `enabled.set(true)` opt an env in (at
        // either scope — the global `enabled` enables every env that
        // hasn't explicitly opted out)
        spec.enabled.convention(project.provider {
            inheritedSpecs(project, environmentId)
                .firstNotNullOfOrNull { it.enabled.orNull }
                ?: global.enabled.orNull ?: false
        })

        // source convention: an inherited env's source, else the
        // global spec's, else unset — the env then compiles at its
        // own requirement
        spec.source.convention(project.provider {
            inheritedSpecs(project, environmentId)
                .firstNotNullOfOrNull { it.source.orNull }
                ?: global.source.orNull
        })

        // target convention: an inherited env's target, else the
        // global spec's emit level (downgradeTo wins over target,
        // same as env scope), else the env's own Minecraft Java floor
        // (the version-json/capability javaVersion — deliberately NOT
        // `requiredJavaVersion`, which a raised `javaVersion` inflates;
        // downgrading exists to compile newer while the emitted
        // bytecode stays readable by the era runtime), with ≤1.6.4-era
        // versions floored at Java 7
        spec.target.convention(project.provider {
            inheritedSpecs(project, environmentId)
                .firstNotNullOfOrNull { it.target.orNull }
                ?: global.downgradeTo.orNull?.majorVersion?.toInt()
                ?: global.target.orNull
                ?: environment.version.orNull?.takeIf(::eraJava7Floor)?.let { 7 }
                ?: project.extensions.getByType(MinecraftExtension::class.java)
                    .artifacts(environmentId).get().capabilities.get().javaVersion
        })

        // `source` raises the env's compile level without touching the
        // run JVM floor — compileJavaVersion drives toolchain
        // selection + release/source/target; requiredJavaVersion keeps
        // feeding launchers and tool-exec
        environment.compileJavaVersion.set(project.provider {
            spec.source.orNull ?: environment.requiredJavaVersion.get()
        })

        // `target` stays the canonical emit level — `downgradeTo`
        // (jvmdg's engine-level name for it) conventions from it, so a
        // direct `downgradeTo` set wins over `target`
        spec.downgradeTo.convention(spec.target.map(JavaVersion::toVersion))

        // the java-api stub jar serves double duty: Flags.api at
        // downgrade time AND a runtime classpath entry — emitted
        // classes call `xyz.wagyourtail.jvmdg.jdk.stub.*` and would
        // NoClassDefFoundError without it. A named resolvable
        // configuration (not detached — detached configs can't
        // serialize under the configuration cache) resolving lazily
        // at execution; NON-transitive — see GradlePlugin for why the
        // api pom's runtime dep on jvmdowngrader core must not resolve
        val capId = environmentId.replaceFirstChar { it.uppercase() }
        val javaApiConfName = "jvmdgJavaApi$capId"
        project.configurations.register(javaApiConfName) { conf ->
            conf.isCanBeConsumed = false
            conf.isTransitive = false
        }
        project.dependencies.add(
            javaApiConfName,
            project.uniminedTools().jvmdowngraderJavaApi.get()
        )

        // ShadeFlags.apiJar resolves the named configuration lazily —
        // the `named` provider serializes by name and `.files`
        // resolves at fingerprint/materialization, same shape as the
        // task inputs below. A global `apiJar` set overrides the
        // default stub jar for envs that don't set their own
        spec.apiJar.convention(global.apiJar.orElse(
            project.configurations.named(javaApiConfName)
                .map { it.files.toList() }
        ))

        // CC: task file inputs may only carry serializable shapes —
        // `from(provider { })` stores the live object graph
        // (DefaultSourceSet → container → Project, Configuration
        // internals) which CC rejects. `classesDirs` itself is a
        // collection of task-output providers — serializable AND it
        // keeps the compile-task dependency edge (`.files` would
        // sever it → implicit-dependency validation failure); the
        // configurations/classpaths feed in as provider-of-plain-files
        // (`named().map { ...files }`) — resolves lazily at fingerprint
        // and the resolved Set<File> serializes, unlike Configuration
        // or FileCollection objects. The env's full
        // `sourceSet.runtimeClasspath` (not the bare configuration)
        // carries unimined's `+=` additions — dev MC, remapped mod
        // artifacts, provider extras — which supertype/stub resolution
        // needs.
        val sourceSets = project.extensions
            .getByType(JavaPluginExtension::class.java).sourceSets
        val envSourceSet = sourceSets.findByName(environmentId)

        val downgradedRoot = project.layout.buildDirectory
            .dir("unimined/downgradedClasses/$environmentId")
        // verb<Env>Noun naming: `downgradeClasses` for `main`
        // (elided), `downgrade<Fabric>Classes` otherwise;
        // `downgradeMainClasses` stays as a deprecated alias
        val downgradeTask = project.tasks.register(
            if (environmentId == "main") "downgradeClasses"
            else "downgrade${capId}Classes",
            DowngradeClassesTask::class.java
        ) { t ->
            t.description =
                "Downgrade $environmentId classes to jvmdowngrader.target"
            t.downgradeEnabled.set(spec.enabled)
            t.requiredJava.set(environment.requiredJavaVersion)
            t.compileJava.set(environment.compileJavaVersion)
            // the env's ShadeFlags convention onto the task's own —
            // `downgradeTo`, `apiJar` and every engine knob the user
            // set on `jvmdowngrader { }` flows through in one call
            t.convention(spec)
            if (envSourceSet != null) {
                // the classpath input resolves the full runtimeClasspath
                // (incl. resources dir) — the classes lifecycle edge
                // keeps implicit-dependency validation satisfied
                t.dependsOn(envSourceSet.classesTaskName)
                t.inputDirs.from(envSourceSet.output.classesDirs)
                t.classpath.from(
                    sourceSets.named(environmentId)
                        .map { it.runtimeClasspath.files })
            }
            t.outputDir.set(downgradedRoot)
        }
        if (environmentId == "main") {
            project.registerDeprecatedTaskAlias(
                "downgradeMainClasses", downgradeTask)
        }

        transform.sourceSet(environmentId).runTaskMutators.add(
            RunMutator(project, environment, downgradeTask,
                downgradedRoot, runs)
        )

        // prod coverage: dev runs get downgraded *dirs* via the
        // mutator above, but prod runs and published artifacts consume
        // the remapped jar — contribute the downgrade to the env's
        // JAR pipeline so it can't ship raw modern bytecode. Deferred:
        // enabled/source/target may not be set yet at wiring time.
        // ModArtifactOnly keeps it off the MC artifact (already era
        // bytecode); ENVIRONMENT_ARTIFACT scope keeps it off remapped
        // dependency views — it lands only in the env's own prod jar.
        remap.sourceSet(environmentId).contributeDeferredSteps(
            project.provider {
                if (!spec.enabled.get()) return@provider emptyList()
                // emit level follows `downgradeTo` (chains `target`)
                // so a direct engine-level override is honored too
                val emit = spec.downgradeTo.get().majorVersion.toInt()
                if (environment.compileJavaVersion.get() <= emit) {
                    emptyList()
                } else listOf(
                    DeferredStepContribution(
                        RemapSurface.JAR,
                        project.objects.newInstance(
                            JvmDowngradeEnvJarStepSpec::class.java
                        ).apply {
                            // the env's whole flag surface conventions
                            // onto the step spec — `downgradeTo`,
                            // `apiJar`, `ignoreWarningsIn`,
                            // `multiReleaseVersions`, … all flow to
                            // the prod jar's downgrade via toFlags()
                            convention(spec)
                            // the shaded api jar is structural — dev
                            // class dirs + prod jars both reference
                            // the stub packages it carries — so it is
                            // not conventioned from `shadeInlining`
                            // (that engine flag decides whether stubs
                            // are inlined into classes instead)
                            shade.set(true)
                        },
                        scope = StepScope.ENVIRONMENT_ARTIFACT
                    )
                )
            }
        )
    }

    /** The `jvmdowngrader` specs of the envs this env inherits
     *  transforms from (`combineWith`/`copyFrom` parents), nearest
     *  first — resolved per project path so cross-project parents
     *  work too. */
    private fun inheritedSpecs(
        project: Project, environmentId: String
    ): List<JvmDgEnvSpec> {
        val transform = project.extensions.getByType(TransformExtension::class.java)
        return transform.sourceSet(environmentId)
            .inheritedEnvironmentReferences()
            .mapNotNull { ref ->
                runCatching {
                    project.rootProject.project(ref.projectPath).extensions
                        .getByType(MinecraftExtension::class.java)
                        .environments.findByName(ref.environmentId)
                        ?.extensions?.getByType(JvmDgEnvSpec::class.java)
                }.getOrNull()
            }
    }

    /** `run<Name>` task -> `Name` -> runs container -> RunConfig.
     *  Production run classpaths are remapped-jar based — dev classes
     *  must never land there. */
    private fun isProduction(taskName: String, runs: RunsExtension): Boolean {
        val runName = taskName.removePrefix("run")
            .replaceFirstChar { it.lowercase() }
        return runs.runs.findByName(runName)?.prod?.getOrElse(false) == true
    }

    private class RunMutator(
        private val project: Project,
        private val environment: MinecraftEnvironment,
        private val downgradeTask: TaskProvider<DowngradeClassesTask>,
        private val downgradedRoot: Provider<Directory>,
        private val runs: RunsExtension
    ) : Action<org.gradle.api.Task> {

        override fun execute(task: org.gradle.api.Task) {
            if (task !is MinecraftRunTask) return
            if (isProduction(task.name, runs)) return
            // run-task mutators compose inherited-first: an ancestor
            // env's mutator fires on descendant runs too. The run's own
            // env executes LAST — let it win (setFrom overwrites the
            // classpath provider; the stamp guards exact re-entry)
            val environmentId = environment.name
            val extras = task.extensions.extraProperties
            if (extras.has(STAMP) && extras.get(STAMP) == environmentId) return
            extras.set(STAMP, environmentId)
            task.dependsOn(downgradeTask)
            // setFrom, not from — additive `from` would leave the raw
            // classes dirs alongside the mirrors (FML then fails on
            // duplicate mods). CC: every object the provider chain
            // captures must serialize — `named` providers (by name)
            // and layout providers do; `this` (the mutator's
            // project/environment/task fields), Configurations and
            // fileTrees do not — so the chain captures only
            // `mirrorRoot`. The transformer resolves the sourceSet's
            // FULL runtimeClasspath to plain files at fingerprint —
            // rebuilding it from configuration+dirs would drop the
            // `+=` additions unimined wires on (loader jar, MC). The
            // substitution gates on the downgrade output's presence
            // (mirrors exist iff the task ran: enabled &&
            // requiredJava > target), never on the flags themselves.
            val mirrorRoot = downgradedRoot
            val sourceSets = project.extensions
                .getByType(JavaPluginExtension::class.java).sourceSets
            val envSourceSet = sourceSets.named(environmentId)
            task.runtimeClasspath.setFrom(envSourceSet.map { sourceSet ->
                val dirs = sourceSet.output.classesDirs.files
                    .filter { it.isDirectory }
                val base = sourceSet.runtimeClasspath.files
                val root = mirrorRoot.get().asFile
                val mirrorDirs = dirs.mapIndexed { i, _ ->
                    File(root, i.toString())
                }
                if (dirs.none { it in base } ||
                    mirrorDirs.none { it.isDirectory })
                    return@map base
                // api-*.jar are the task-emitted target-downgraded stub
                // jars — never the raw java-api artifact (its stubs are
                // compiled at their own JDK's version and era loaders
                // can neither scan nor load them)
                val apiMirrors = root.listFiles { f ->
                    f.name.startsWith("api-") && f.name.endsWith(".jar")
                }?.toList() ?: emptyList()
                base.filter { it !in dirs } + mirrorDirs + apiMirrors
            })
        }

    }

    private const val STAMP = "unimined.jvmdg.env"

    /** ≤1.6.4 (incl. b/a/c/rd-prefixed era ids) caps at Java 7 —
     *  the era's bundled ASM can't read v52 class files. */
    private fun eraJava7Floor(version: String): Boolean {
        val v = version.trim()
        if (!v.first().isDigit() || !v.startsWith("1.")) return true
        val parts = v.removePrefix("1.").split('.', '-', '_')
        val minor = parts.getOrNull(0)?.toIntOrNull() ?: return false
        val patch = parts.getOrNull(1)?.toIntOrNull() ?: 0
        return minor < 6 || (minor == 6 && patch <= 4)
    }

}
