package xyz.wagyourtail.unimined.publish.modpublisher

import org.gradle.api.Plugin
import org.gradle.api.Project
import java.io.File

/**
 * Plugin id `xyz.wagyourtail.unimined.publish-modpublisher` — feeds
 * production artifacts + metadata into ModPublisher's own
 * `publisher { }` DSL as *conventions*: the merged forgix artifact
 * wins when present, else per-loader production jars; loaders, game
 * versions and the project version convention in too. Dev artifacts
 * are never selectable.
 *
 * There is no adapter DSL — `publisher { }` stays the only surface
 * for what can't be inferred (credentials, platform ids, changelog,
 * version type, display name, extra files); a user-set value always
 * beats the convention.
 *
 * The ModPublisher plugin itself
 * (`com.hypherionmc.modutils.modpublisher`) is applied by the
 * consuming build; this adapter activates only when it is present.
 */
class GradlePlugin : Plugin<Project> {

    override fun apply(target: Project) {
        // env-bound collection — populated under the minecraft gate,
        // read lazily by the publisher conventions below so plugin
        // ordering doesn't matter
        val bound = mutableListOf<Pair<
                xyz.wagyourtail.unimined.minecraft.MinecraftEnvironment,
                org.gradle.api.tasks.SourceSet>>()
        target.pluginManager.withPlugin("xyz.wagyourtail.unimined.minecraft") {
            val minecraft = target.extensions.getByType(
                xyz.wagyourtail.unimined.minecraft.MinecraftExtension::class.java)
            minecraft.onEnvironmentBound { env, sourceSet ->
                bound += env to sourceSet
            }
        }

        // mergedElements artifact — materializes only when the forgix
        // adapter AND forgix itself are applied; resolved lazily
        var mergedArtifact: org.gradle.api.provider.Provider<File>? = null
        target.pluginManager.withPlugin("xyz.wagyourtail.unimined.adapter-forgix") {
            mergedArtifact = target.provider {
                target.configurations.findByName("mergedElements")
                    ?.outgoing?.artifacts?.firstOrNull()?.file
            }
        }

        target.pluginManager.withPlugin("com.hypherionmc.modutils.modpublisher") {
            ModpublisherWiring.wire(target, bound, mergedArtifact)
        }
    }

}
