package xyz.wagyourtail.unimined.adapter.expectplatform

import org.gradle.api.Project
import org.gradle.api.provider.Provider
import xyz.wagyourtail.unimined.expect.TransformPlatform
import xyz.wagyourtail.unimined.minecraft.MinecraftEnvironment
import xyz.wagyourtail.unimined.minecraft.MinecraftExtension
import xyz.wagyourtail.unimined.remap.RemapExtension
import xyz.wagyourtail.unimined.remap.spi.DeferredStepContribution
import xyz.wagyourtail.unimined.remap.spi.RemapSurface
import xyz.wagyourtail.unimined.remap.spi.StepSpec
import xyz.wagyourtail.unimined.runs.RunsExtension
import xyz.wagyourtail.unimined.transform.TransformExtension
import java.io.File

/**
 * Per-environment wiring between Unimined's run/remap seams and
 * ExpectPlatform. Registered by [GradlePlugin] for every env once
 * both plugins are present.
 */
internal object ExpectPlatformEnvWiring {

    fun wire(
        project: Project,
        remap: RemapExtension,
        env: MinecraftEnvironment,
        agentJar: Provider<File>,
        runs: RunsExtension,
        transform: TransformExtension
    ) {
        val envId = env.environmentId
        val spec = env.extensions.create(
            "expectPlatform",
            ExpectPlatformEnvExtension::class.java, project, env)

        // inheritance — nearest inherited env's values convention in;
        // explicit settings always win
        spec.enabled.convention(project.provider {
            inheritedSpecs(project, envId, transform)
                .firstNotNullOfOrNull { it.enabled.orNull } ?: true
        })
        spec.agent.convention(project.provider {
            inheritedSpecs(project, envId, transform)
                .firstNotNullOfOrNull { it.agent.orNull } ?: true
        })
        spec.platformName.convention(project.provider {
            inheritedSpecs(project, envId, transform)
                .firstNotNullOfOrNull { it.platformName.orNull }
        })
        // effective remap — built-in loader table < inherited env map
        // < explicit `remap` entries (later layers win per key)
        val effectiveRemap = project.provider {
            (spec.platformName.orNull?.let {
                EnvironmentRemapTables.forLoader(it, env.version.orNull) }
                ?: EnvironmentRemapTables.forLoader(
                    env.loaderSpec.orNull?.name, env.version.orNull)
                ?: emptyMap()) +
            (inheritedSpecs(project, envId, transform)
                .firstNotNullOfOrNull { it.remap.orNull?.takeIf { m -> m.isNotEmpty() } }
                ?: emptyMap()) +
            (spec.remap.orNull ?: emptyMap())
        }
        spec.effectiveRemap = effectiveRemap
        spec.stripAnnotations.convention(project.provider {
            inheritedSpecs(project, envId, transform)
                .firstNotNullOfOrNull { it.stripAnnotations.orNull } ?: false
        })

        // prod-jar static transform — contributed when the env has a
        // platform; resolves lazily at pipeline materialization
        remap.sourceSet(envId).contributeDeferredSteps(project.provider {
            if (!spec.active()) emptyList()
            else {
                val step = project.objects.newInstance(
                    ExpectPlatformStepSpec::class.java)
                step.platformName.set(spec.platformName)
                step.remap.set(effectiveRemap)
                step.stripAnnotations.set(spec.stripAnnotations)
                listOf(DeferredStepContribution(RemapSurface.JAR,
                    step as StepSpec))
            }
        })

        // env-bound runs: agent + sysprops, gated so enabled/agent
        // opt-outs resolve at execution
        runs.runs.all { run ->
            if (run.environment.orNull == envId) {
                run.javaagents.from(project.provider {
                    if (spec.active()) listOf(agentJar.get())
                    else emptyList<Any>()
                })
                run.jvmArgs.addAll(project.provider {
                    if (!spec.active()) emptyList()
                    else listOfNotNull(
                        "-Dexpect.platform=${spec.platformName.get()}",
                        run.side.orNull?.let {
                            "-Dexpect.side=${it.name.lowercase()}" },
                        effectiveRemap.get().takeIf { it.isNotEmpty() }?.let {
                            "-Dexpect.remap=${TransformPlatform.mapToString(it)}" }
                    )
                })
            }
        }
    }

    private fun ExpectPlatformEnvExtension.active() =
        enabled.getOrElse(true) && agent.getOrElse(true) &&
                platformName.isPresent

    private fun inheritedSpecs(
        project: Project, environmentId: String,
        transform: TransformExtension
    ): List<ExpectPlatformEnvExtension> =
        transform.sourceSet(environmentId)
            .inheritedEnvironmentReferences()
            .mapNotNull { ref ->
                runCatching {
                    project.rootProject.project(ref.projectPath).extensions
                        .getByType(MinecraftExtension::class.java)
                        .environments.findByName(ref.environmentId)
                        ?.extensions?.findByType(
                            ExpectPlatformEnvExtension::class.java)
                }.getOrNull()
            }

}
