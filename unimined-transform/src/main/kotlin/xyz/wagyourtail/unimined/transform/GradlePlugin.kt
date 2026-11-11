package xyz.wagyourtail.unimined.transform

import org.gradle.api.Plugin
import org.gradle.api.Project
import xyz.wagyourtail.unimined.api.util.uniminedAdopt
import xyz.wagyourtail.unimined.minecraft.MinecraftExtension
import xyz.wagyourtail.unimined.transform.internal.EnvironmentClasspathWiring
import xyz.wagyourtail.unimined.transform.loader.LoaderRegistry

/**
 * Plugin id `xyz.wagyourtail.unimined.transform`.
 */
class GradlePlugin : Plugin<Project> {

    override fun apply(target: Project) {
        target.extensions.create("uniminedTransform", TransformExtension::class.java, target)
        target.uniminedAdopt("transform", TransformExtension::class.java)
        target.pluginManager.withPlugin("xyz.wagyourtail.unimined") {
            target.uniminedAdopt("transform", TransformExtension::class.java)
        }

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
                        val descriptor = LoaderRegistry.resolveBuiltin(spec.name)
                            ?: throw org.gradle.api.GradleException(
                                "unimined: unknown loader '${spec.name}' in " +
                                "environment '${environment.environmentId}' — " +
                                "check the loader(...) name spelling"
                            )
                        val product = environment.product.get()
                        if (!descriptor.supportsProduct(product)) {
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
