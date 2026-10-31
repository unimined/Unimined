package xyz.wagyourtail.unimined.publish.maven

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.component.AdhocComponentWithVariants
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import xyz.wagyourtail.unimined.publish.PublishExtension

/**
 * Plugin id `xyz.wagyourtail.unimined.publish-maven` — creates a
 * `MavenPublication` for each `unimined<Env>` adhoc component the
 * publish host produces, with coordinates from
 * `uniminedPublish { environment("<env>") { } }`.
 *
 * Callers refine the publication via the standard Gradle API:
 * `publishing.publications.named("uniminedMain") { pom { } }` —
 * repository credentials/destinations stay on `publishing.repositories`.
 * The `unimined-mod-loader-merge` merged artifact is never routed here
 * (plan §11) — it is a ModPublisher-path artifact only.
 */
class GradlePlugin : Plugin<Project> {

    override fun apply(target: Project) {
        target.pluginManager.apply("maven-publish")

        target.pluginManager.withPlugin("xyz.wagyourtail.unimined.publish") {
            val ext = target.extensions.getByType(PublishExtension::class.java)
            val publishing =
                target.extensions.getByType(PublishingExtension::class.java)

            // env names derive from component names: uniminedMain -> main,
            // uniminedFabric -> fabric (uncapitalized)
            target.components.withType(AdhocComponentWithVariants::class.java) { comp ->
                val name = comp.name
                if (!name.startsWith("unimined")) return@withType
                val env = name.removePrefix("unimined")
                    .replaceFirstChar(Char::lowercase)
                val envPub = ext.environments[env]

                publishing.publications.maybeCreate(name, MavenPublication::class.java).apply {
                    from(comp)
                    envPub?.groupId?.let { groupId = it }
                    envPub?.artifactId?.let { artifactId = it }
                    envPub?.version?.let { version = it }
                }
            }
        }
    }

}
