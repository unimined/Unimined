package xyz.wagyourtail.unimined.transform.fabric

import org.gradle.api.Plugin
import org.gradle.api.Project
import xyz.wagyourtail.unimined.minecraft.MinecraftExtension
import xyz.wagyourtail.unimined.transform.fabric.internal.FabricEnvironmentWiring
import xyz.wagyourtail.unimined.transform.fabric.internal.FabricModAnalysis
import java.net.URI

/**
 * Plugin id `xyz.wagyourtail.unimined.transform.fabric`.
 *
 * Loader-gated reference transform: contributes the fabric Maven repo and
 * `intermediary` mod-source default, then wires each bound environment's
 * loader dependency, intermediary mapping hop, access-widener pipeline
 * steps, and Knot launch main class — only when the environment declares
 * `loader("fabric", ...)`.
 */
class GradlePlugin : Plugin<Project> {

    override fun apply(target: Project) {
        target.pluginManager.withPlugin("xyz.wagyourtail.unimined.minecraft") {
            val minecraft = target.extensions.getByType(MinecraftExtension::class.java)
            minecraft.loaderModSourceNamespaces["fabric"] = listOf("intermediary")
            target.repositories.maven { it.url = URI.create(FabricModAnalysis.FABRIC_MAVEN) }
            // loader repos for loaders actually selected — registering
            // the whole family's repos eagerly would add unrelated (and
            // potentially unreachable) mavens to every resolution
            target.afterEvaluate {
                val registry = xyz.wagyourtail.unimined.transform.loader.LoaderRegistry()
                minecraft.environments.forEach { env ->
                    val loader = env.loaderSpec.orNull ?: return@forEach
                    registry.resolve(loader.name)?.repos?.forEach { repo ->
                        repo.registerOn(it.repositories)
                    }
                }
            }
            target.pluginManager.withPlugin("xyz.wagyourtail.unimined.transform") {
                target.pluginManager.withPlugin("xyz.wagyourtail.unimined.remap") {
                    minecraft.onEnvironmentBound { environment, _ ->
                        val spec = target.extensions.getByType(
                        xyz.wagyourtail.unimined.transform.TransformExtension::class.java
                    ).sourceSet(environment.environmentId)
                    spec.installClaimContext(target, {
                        environment.loaderSpec.takeIf { it.isPresent }?.get()
                            ?.let { xyz.wagyourtail.unimined.transform.loader.LoaderRegistry().resolve(it.name) }
                    }, { environment })
                        FabricEnvironmentWiring.wire(target, environment)
                    }
                }
            }
        }
    }

}
