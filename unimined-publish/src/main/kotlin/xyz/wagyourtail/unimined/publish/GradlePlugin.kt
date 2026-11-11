package xyz.wagyourtail.unimined.publish

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.component.AdhocComponentWithVariants
import xyz.wagyourtail.unimined.api.util.afterDslEval
import xyz.wagyourtail.unimined.api.util.uniminedAdopt
import xyz.wagyourtail.unimined.minecraft.MinecraftExtension
import xyz.wagyourtail.unimined.publish.internal.MavenPublicationWiring
import xyz.wagyourtail.unimined.publish.internal.PublishWiring

/**
 * Plugin id `xyz.wagyourtail.unimined.publish` — publisher host.
 *
 * Creates one `AdhocComponentWithVariants` per bound source-set/loader
 * environment (populated from that env's outgoing API/runtime/source
 * configurations) and exposes `uniminedPublish { }`.
 *
 * Maven publication is built in: `maven-publish` is applied eagerly so
 * `publishing { repositories { } }` works during project evaluation,
 * and a `MavenPublication` is created per environment component
 * (`uniminedMain`, `uniminedFabric`, ...) as they bind — one Maven
 * module per environment/loader. `uniminedPublish { mavenPublications
 * .set(false) }` opts out of publication creation entirely; the
 * ordinary `components.java` publication stays opt-in via
 * `uniminedPublish { javaPublication() }` (plan §11). Publication
 * customization (`pom { }`, `publications.named(...)`) runs in
 * `afterEvaluate`, once the env components exist.
 */
class GradlePlugin : Plugin<Project> {

    override fun apply(target: Project) {
        val ext = target.extensions.create(
            "uniminedPublish", PublishExtension::class.java
        )
        ext.javaPublication.convention(false)
        ext.mavenPublications.convention(true)
        target.uniminedAdopt("publish", PublishExtension::class.java)
        target.pluginManager.withPlugin("xyz.wagyourtail.unimined") {
            target.uniminedAdopt("publish", PublishExtension::class.java)
        }

        // applied eagerly so the `publishing { }` extension (repositories,
        // publication customization) exists while the build script runs;
        // unimined publications themselves appear post-DSL with the env
        // components. Opting out of mavenPublications skips creating them
        // (including the javaPublication() opt-in) — the publishing
        // extension stays available for the user's own publications.
        target.pluginManager.apply("maven-publish")

        // created in afterEvaluate — before user afterEvaluate blocks —
        // so `publishing.publications.named("java") { }` resolves
        target.afterDslEval {
            if (ext.mavenPublications.get() && ext.javaPublication.get()) {
                MavenPublicationWiring.addJavaPublication(target)
            }
        }

        // env components are created in afterEvaluate (aux-env filtering
        // needs the finished DSL) — this live rule fires as each one is
        // added, which is still before user afterEvaluate blocks, so
        // `publishing.publications.named("unimined<Env>")` resolves there.
        // mavenPublications is read per component: by then the DSL has
        // run, so the property value is final.
        target.components.withType(AdhocComponentWithVariants::class.java) { comp ->
            if (!ext.mavenPublications.get()) return@withType
            MavenPublicationWiring.addPublication(target, ext, comp)
        }

        target.gradle.projectsEvaluated {
            if (ext.mavenPublications.get()) {
                MavenPublicationWiring.finish(target, ext)
            }
        }

        target.pluginManager.withPlugin("xyz.wagyourtail.unimined.minecraft") {
            val minecraft = target.extensions.getByType(MinecraftExtension::class.java)
            minecraft.onEnvironmentBound { environment, sourceSet ->
                // aux envs (datagen/gametest) are test harnesses that
                // produce no publishable artifact — env binding fires
                // before datagen()/gametest() mark isAuxEnv, so the
                // check must run post-DSL
                target.afterDslEval {
                    if (!environment.isAuxEnv.get()) {
                        PublishWiring.wire(target, environment, sourceSet, ext)
                    }
                }
            }
        }
    }

}
