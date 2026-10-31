package xyz.wagyourtail.unimined.publish.internal

import org.gradle.api.Project
import org.gradle.api.component.AdhocComponentWithVariants
import org.gradle.api.component.SoftwareComponentFactory
import org.gradle.api.tasks.SourceSet
import xyz.wagyourtail.unimined.minecraft.MinecraftEnvironment
import xyz.wagyourtail.unimined.publish.PublishExtension
import javax.inject.Inject

/**
 * Wires one `AdhocComponentWithVariants` per bound environment, named
 * `unimined<Env>` (`uniminedMain` for the default env), populated from
 * the environment's outgoing `apiElements`/`runtimeElements`/
 * `sourcesElements`-style configurations when present.
 */
internal object PublishWiring {

    fun wire(
        project: Project,
        environment: MinecraftEnvironment,
        sourceSet: SourceSet,
        ext: PublishExtension
    ) {
        val env = environment.environmentId
        val componentName = if (env == "main") "uniminedMain" else "unimined${env.cap()}"
        val envPub = ext.environmentsInternal.getOrPut(env) {
            PublishExtension.EnvironmentPublication(env)
        }

        val factory = project.objects.newInstance(ComponentFactoryHolder::class.java).factory
        val component = factory.adhoc(componentName)

        // the env's standard outgoing configurations — dev/prod jars,
        // sources, and mod-support data variants land on their own
        // configurations upstream; we surface every existing one
        val outgoing = listOf(
            "${sourceSet.name}ApiElements",
            "${sourceSet.name}RuntimeElements",
            "${sourceSet.name}SourcesElements",
            "${sourceSet.name}JavadocElements",
        )
        for (confName in outgoing) {
            project.configurations.findByName(confName)?.let { conf ->
                component.addVariantsFromConfiguration(conf) { details ->
                    // keep the environment variant semantics — no
                    // remapping to a different usage/category here
                }
            }
        }

        project.components.add(component)

        // stash the per-env settings on the component's convention
        // object so adapters (publish-maven / -modpublisher) can read
        // coordinates + variant toggles at publication time
        project.extensions.extraProperties.set(
            "uniminedPublish.$env",
            envPub
        )
    }

    /** Holds the injected [SoftwareComponentFactory] — Gradle injects it. */
    abstract class ComponentFactoryHolder @Inject constructor(
        val factory: SoftwareComponentFactory
    )

    private fun String.cap(): String = replaceFirstChar(Char::uppercase)

}
