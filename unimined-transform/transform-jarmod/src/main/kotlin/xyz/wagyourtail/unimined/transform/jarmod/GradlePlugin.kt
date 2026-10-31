package xyz.wagyourtail.unimined.transform.jarmod

import org.gradle.api.Plugin
import org.gradle.api.Project
import xyz.wagyourtail.unimined.minecraft.MinecraftExtension
import xyz.wagyourtail.unimined.transform.jarmod.internal.JarModEnvironmentWiring
import xyz.wagyourtail.unimined.transform.loader.LoaderRegistry
import java.net.URI

/**
 * Plugin id `xyz.wagyourtail.unimined.transform.jarmod`.
 *
 * Loader-gated jarmod transform: for environments declaring
 * `loader("jarmod", <JarModAgent version>)`, wires the `jarMod<env>`
 * configuration into a merge step that injects its contents into the
 * dev Minecraft jar (1.x `JarModMinecraftTransformer` semantics —
 * this is also the substrate FG1 Forge and ModLoader builds reuse).
 * `JarModAgent` itself is the loader product; agent wiring for runs is
 * a follow-up phase.
 */
class GradlePlugin : Plugin<Project> {

    override fun apply(target: Project) {
        target.pluginManager.withPlugin("xyz.wagyourtail.unimined.minecraft") {
            val minecraft = target.extensions.getByType(MinecraftExtension::class.java)
            // jarmod mods run in the vanilla jar's own namespace
            minecraft.loaderModSourceNamespaces["jarmod"] = listOf("official")
            listOf(
                "https://maven.wagyourtail.xyz/releases/",
                "https://maven.wagyourtail.xyz/snapshots/"
            ).forEach {
                target.repositories.maven { r -> r.url = URI.create(it) }
            }
            // env-registration-time: configs must exist before the
            // script's `dependencies {}` block evaluates; the `jarmod`
            // extension must also exist before `minecraft { }` bodies
            // run so Groovy DSL can dispatch env.jarmod(...) etc.
            minecraft.environments.all { environment ->
                environment.extensions.add("jarmod", JarModEnvExtension(environment))
                JarModEnvironmentWiring.createConfigurations(target, environment)
            }
            // liteloader envs need artifact-only repos + the legacy
            // redist ivy feed (pattern layout, no metadata) — gated on
            // a liteloader env existing since loaderSpec populates
            // during script eval
            target.afterEvaluate {
                if (minecraft.environments.any {
                    it.spec().loader?.name == "liteloader"
                }) {
                    target.repositories.maven { r ->
                        r.url = URI.create("https://dl.liteloader.com/versions/")
                        r.metadataSources { s -> s.artifact() }
                    }
                    target.repositories.maven { r ->
                        r.url = URI.create("https://repo.spongepowered.org/maven/")
                    }
                    target.repositories.ivy { r ->
                        r.url = URI.create("https://dl.liteloader.com/redist/legacy/")
                        r.patternLayout { p -> p.artifact("[artifact]_[revision].[ext]") }
                        r.content { c -> c.includeModule("com.mumfrey", "liteloader") }
                        r.metadataSources { s -> s.artifact() }
                    }
                }
            }
            target.pluginManager.withPlugin("xyz.wagyourtail.unimined.transform") {
                minecraft.onEnvironmentBound { environment, _ ->
                        val spec = target.extensions.getByType(
                        xyz.wagyourtail.unimined.transform.TransformExtension::class.java
                    ).sourceSet(environment.environmentId)
                    spec.installClaimContext(target, {
                        environment.loaderSpec.takeIf { it.isPresent }?.get()
                            ?.let { xyz.wagyourtail.unimined.transform.loader.LoaderRegistry().resolve(it.name) }
                    }, { environment })
                    // loaderSpec populates during script eval, after the
                    // bound event fires — defer the loader check
                    target.afterEvaluate {
                        if (environment.spec().loader?.name in setOf("jarmod", "fg1")) {
                            JarModEnvironmentWiring.wire(target, environment)
                        }
                    }
                }
            }
        }
    }

}
