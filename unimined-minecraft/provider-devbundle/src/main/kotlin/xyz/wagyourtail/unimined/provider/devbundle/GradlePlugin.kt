package xyz.wagyourtail.unimined.provider.devbundle

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.dsl.RepositoryHandler
import org.gradle.api.artifacts.repositories.MavenArtifactRepository
import xyz.wagyourtail.unimined.api.spec.DeclaredSource
import xyz.wagyourtail.unimined.api.util.afterDslEval
import xyz.wagyourtail.unimined.api.util.reportProblem
import xyz.wagyourtail.unimined.mapping.UniminedRepos
import xyz.wagyourtail.unimined.minecraft.MinecraftExtension
import xyz.wagyourtail.unimined.provider.devbundle.internal.DevBundleExportWiring
import java.io.File

/**
 * Plugin id `xyz.wagyourtail.unimined.provider.devbundle`.
 *
 * Paperweight-format dev-bundle provider: environments whose
 * `legacydist {}` declared source is a dev-bundle zip (marked
 * `format = "devbundle"`, or the env's `provider` is explicitly
 * `"devbundle"`) get their server artifact produced by running the
 * bundle's embedded paperclip against the vanilla server jar.
 *
 * Also contributes the producer side: a `devBundleExport` spec on
 * every env + `export<Env>DevBundle` task writing a consumable bundle
 * of the env's production artifact (paperweight's `GenerateDevBundle`
 * analog).
 */
class GradlePlugin : Plugin<Project> {

    override fun apply(target: Project) {
        // bundle library coordinates resolve against the usual
        // suspects: central + Mojang's host for vanilla deps, Paper's
        // public repo for patched/bundled artifacts — same baseline
        // paperweight ensures
        val repos = UniminedRepos(target)
        repos.mavenCentral.ensure()
        repos.mojangLibraries.ensure()
        repos.paper.ensure()
        target.pluginManager.withPlugin("xyz.wagyourtail.unimined.minecraft") {
            // export surface — the `devBundleExport` spec +
            // `export<Env>DevBundle` task on every env
            target.extensions.getByType(MinecraftExtension::class.java)
                .environments.all { environment ->
                    DevBundleExportWiring.wire(target, environment)
                }
            // bundles self-declare extra `libraryRepositories` (fork hosts,
            // staging mavens) — mirror them once the DSL settles; only
            // local file sources are readable at this point
            target.afterDslEval {
                val minecraft =
                    target.extensions.getByType(MinecraftExtension::class.java)
                for (env in minecraft.environments) {
                    val spec = env.legacyDist
                    if (spec.format != DevBundleProvider.FORMAT &&
                        env.provider.orNull != DevBundleProvider.NAME
                    ) continue
                    // read the raw declared source — declaration()
                    // would resolve maven sources eagerly
                    val source = spec.source
                    if (source !is DeclaredSource.LocalFile) continue
                    val config = runCatching {
                        DevBundleProduction.readConfig(File(source.path).toPath())
                    }.onFailure {
                        target.reportProblem(
                            "devbundle/read-config",
                            "unimined: could not read declared bundle " +
                                    "at ${source.path} for " +
                                    "libraryRepositories",
                            it.message
                        )
                    }.getOrNull() ?: continue
                    config.libraryRepositories.forEach {
                        ensureMavenRepo(target.repositories, it)
                    }
                }
            }
        }
    }

    private fun ensureMavenRepo(handler: RepositoryHandler, url: String) {
        val normalized = url.trimEnd('/')
        val present = handler.any {
            (it as? MavenArtifactRepository)
                ?.url?.toString()?.trimEnd('/') == normalized
        }
        if (!present) {
            handler.maven { it.setUrl(normalized) }
        }
    }

}
