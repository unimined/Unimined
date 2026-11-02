package xyz.wagyourtail.unimined.transform

import org.gradle.api.Plugin
import org.gradle.api.Project
import xyz.wagyourtail.unimined.minecraft.MinecraftExtension
import xyz.wagyourtail.unimined.transform.internal.EnvironmentClasspathWiring
import xyz.wagyourtail.unimined.transform.loader.LoaderRegistry

/**
 * Plugin id `xyz.wagyourtail.unimined.transform`.
 */
class GradlePlugin : Plugin<Project> {

    override fun apply(target: Project) {
        target.extensions.create("transform", TransformExtension::class.java, target)

        target.pluginManager.withPlugin("xyz.wagyourtail.unimined.minecraft") {
            target.pluginManager.withPlugin("xyz.wagyourtail.unimined.remap") {
                val minecraft = target.extensions.getByType(MinecraftExtension::class.java)
                minecraft.onEnvironmentBound { environment, sourceSet ->
                    EnvironmentClasspathWiring.wire(target, environment, sourceSet)
                }
                // lazy per-loader transform apply — the loader's
                // `LoaderDescriptor.transformPlugin` declares the family
                // transform (e.g. forge); the frontend only stacks the
                // core hosts, so the family plugin must be applied once
                // an env declares the loader. `loaderSpec` isn't set
                // until the `minecraft {}` DSL runs, so evaluate after
                // the project configures — the applied plugin's own
                // `onEnvironmentBound` replays bound environments.
                target.afterEvaluate {
                    for (environment in minecraft.environments) {
                        val spec = environment.loaderSpec.orNull ?: continue
                        val descriptor = LoaderRegistry.resolveBuiltin(spec.name) ?: continue
                        val product = environment.product.get()
                        val supported = descriptor.supportedProducts
                            ?.any {
                                it == "*" || it == product ||
                                        product.startsWith("$it:")
                            }
                            ?: (product ==
                                    xyz.wagyourtail.unimined.api.spec
                                        .ProductSpec.PRODUCT_NATIVE)
                        if (!supported) {
                            throw org.gradle.api.GradleException(
                                "unimined: loader '${spec.name}' does not " +
                                        "support product '$product' " +
                                        "(env '${environment.environmentId}') — " +
                                        "supported: " +
                                        (descriptor.supportedProducts
                                            ?.joinToString(", ")
                                            ?: xyz.wagyourtail.unimined.api.spec
                                                .ProductSpec.PRODUCT_NATIVE)
                            )
                        }
                        descriptor.transformPlugin
                            ?.let { target.pluginManager.apply(it) }
                    }
                }
            }
        }
    }

}
