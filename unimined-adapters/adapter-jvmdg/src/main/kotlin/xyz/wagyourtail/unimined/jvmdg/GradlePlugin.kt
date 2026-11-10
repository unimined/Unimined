package xyz.wagyourtail.unimined.jvmdg

import org.gradle.api.GradleException
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import xyz.wagyourtail.unimined.minecraft.MinecraftExtension
import xyz.wagyourtail.unimined.remap.stepVerb
import xyz.wagyourtail.unimined.runs.RunsExtension
import xyz.wagyourtail.unimined.transform.TransformExtension

/**
 * Plugin id `xyz.wagyourtail.unimined.jvmdg` — the optional
 * JvmDowngrader compat layer. Applying it is what wires jvmdg into
 * Unimined (core carries no jvmdg surface):
 *
 *  - supplies the `downgrade` step on the JAR surface, rewriting
 *    class files to an older class-file major so modern-compiled
 *    artifacts run on old-JVM/old-ASM runtimes
 *  - adds a `jvmdowngrader` extension to every Minecraft environment:
 *    downgrading is **on by default** once the plugin is applied —
 *    `jvmdowngrader(8)` retargets, `jvmdowngrader { enabled.set(false) }`
 *    opts a single env out. Dev `run*` tasks launch against downgraded
 *    class dirs; envs compiling at/below their target pass through.
 */
class GradlePlugin : Plugin<Project> {

    override fun apply(target: Project) {
        target.pluginManager.apply(xyz.wagyourtail.unimined.remap.GradlePlugin::class.java)
        // detached so the api jar resolves lazily at execution, not
        // during configuration — keeps the run offline-reproducible
        // instead of letting jvmdg download it at runtime
        // NON-transitive: the api pom pulls jvmdowngrader core (and its
        // ASM) at runtime scope — resolving transitives would put
        // unpatched ASM on era launch classpaths and shadow FML's
        // patched ASM (era EventTransformer depends on lenient ASM-4
        // Type.getType behaviour)
        val javaApi = target.configurations.detachedConfiguration(
            target.dependencies.create("xyz.wagyourtail.jvmdowngrader:jvmdowngrader-java-api:2.0.1")
        )
        javaApi.isTransitive = false
        target.extensions.getByType(
            xyz.wagyourtail.unimined.remap.RemapExtension::class.java
        ).registerDslVerb("downgrade") { spec ->
            spec.stepVerb(JvmDowngradeStepSpec::class.java) { step, args ->
                step.api.from(javaApi)
                step.shade.convention(true)
                step.target.convention(JavaVersion.VERSION_1_8)
                args.firstOrNull()?.let { arg ->
                    when (arg) {
                        is JavaVersion -> step.target.set(arg)
                        is Number -> step.classVersion.set(arg.toInt())
                        is String -> step.classVersion.set(
                            arg.toIntOrNull()
                                ?: throw GradleException(
                                    "downgrade: '$arg' is not a class-file major version " +
                                            "(expected a JavaVersion or an int like 52)"
                                )
                        )
                        else -> throw GradleException(
                            "downgrade: unsupported argument '$arg' " +
                                    "(expected a JavaVersion or an int like 52)"
                        )
                    }
                }
            }
        }

        // run-task wiring rides the env + run + transform plugins'
        // surfaces — keyed per plugin id (not the umbrella) so modular
        // consumers are covered too
        target.pluginManager.withPlugin("xyz.wagyourtail.unimined.minecraft") {
            target.pluginManager.withPlugin("xyz.wagyourtail.unimined.transform") {
                target.pluginManager.withPlugin("xyz.wagyourtail.unimined.runs") {
                    val minecraft = target.extensions
                        .getByType(MinecraftExtension::class.java)
                    val transform = target.extensions
                        .getByType(TransformExtension::class.java)
                    val runs = target.extensions.getByType(RunsExtension::class.java)
                    minecraft.environments.all { environment ->
                        JvmdgEnvWiring.wire(target, environment, transform, runs)
                    }
                }
            }
        }
    }

}
