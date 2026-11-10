package xyz.wagyourtail.unimined.adapter.expectplatform

import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import xyz.wagyourtail.unimined.expect.ExpectPlatformExtension
import xyz.wagyourtail.unimined.minecraft.MinecraftExtension
import xyz.wagyourtail.unimined.remap.RemapExtension
import xyz.wagyourtail.unimined.remap.stepVerb
import xyz.wagyourtail.unimined.runs.RunsExtension
import xyz.wagyourtail.unimined.transform.TransformExtension

/**
 * Optional ExpectPlatform bridge — activates when BOTH
 * `xyz.wagyourtail.unimined.expect-platform` and
 * `xyz.wagyourtail.unimined` are applied:
 *
 *  - adds an `expectPlatform` extension per environment:
 *    `platform(name, remap)` marks the env's `modImplementation`/
 *    `modRuntimeOnly` configurations for ExpectPlatform's artifact
 *    transform (resolved mod jars get statically transformed);
 *    `platformName`/`remap` convention-chain to inherited envs;
 *    `enabled`/`agent` are per-env opt-outs
 *  - wires every env-bound run with the agent jar + `-Dexpect.
 *    platform`/`-Dexpect.side`/`-Dexpect.remap` — the [RunConfig]
 *    equivalent of ExpectPlatform's `insertAgent(JavaExecSpec)`
 *  - contributes a JAR-surface `expectPlatform` step per env with a
 *    platform set: the prod jar is statically resolved
 *    (`@ExpectPlatform` redirects, `@PlatformOnly` filtering,
 *    annotation remaps) so `runProd*` and shipped artifacts need no
 *    agent — also declared manually via `jar { expectPlatform { } }`
 *
 * ```
 * sourceSet("main") {
 *     expectPlatform { platform("fabric") }
 * }
 * ```
 */
class GradlePlugin : Plugin<Project> {

    override fun apply(project: Project) {
        project.pluginManager.apply(xyz.wagyourtail.unimined.remap.GradlePlugin::class.java)
        val remap = project.extensions.getByType(RemapExtension::class.java)

        remap.registerDslVerb("expectPlatform") { surface ->
            surface.stepVerb(ExpectPlatformStepSpec::class.java) { step, args ->
                step.stripAnnotations.convention(false)
                args.firstOrNull()?.let { arg ->
                    when (arg) {
                        is String -> step.platformName.set(arg)
                        is Map<*, *> -> @Suppress("UNCHECKED_CAST")
                            step.remap.set(arg as Map<String, String>)
                        else -> throw GradleException(
                            "expectPlatform: unsupported argument '$arg' " +
                                    "(expected a platform name string or a remap map)"
                        )
                    }
                }
            }
        }

        project.pluginManager.withPlugin(
            "xyz.wagyourtail.unimined.expect-platform") {
            // env + run + transform surfaces — keyed per plugin id (not
            // the umbrella) so modular consumers are covered too
            project.pluginManager.withPlugin(
                "xyz.wagyourtail.unimined.minecraft") {
                project.pluginManager.withPlugin(
                    "xyz.wagyourtail.unimined.runs") {
                    project.pluginManager.withPlugin(
                        "xyz.wagyourtail.unimined.transform") {
                        val epExt = project.extensions.findByType(
                            ExpectPlatformExtension::class.java)
                            ?: return@withPlugin
                        val agentJar = project.provider { epExt.agentJar }
                        val minecraft = project.extensions.getByType(
                            MinecraftExtension::class.java)
                        val runs = project.extensions.getByType(
                            RunsExtension::class.java)
                        val transform = project.extensions.getByType(
                            TransformExtension::class.java)

                        minecraft.environments.configureEach { env ->
                            ExpectPlatformEnvWiring.wire(
                                project, remap, env, agentJar, runs,
                                transform)
                        }
                    }
                }
            }
        }
    }

}
