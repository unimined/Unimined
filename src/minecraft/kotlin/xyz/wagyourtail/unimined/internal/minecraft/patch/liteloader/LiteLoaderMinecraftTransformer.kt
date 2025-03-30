package xyz.wagyourtail.unimined.internal.minecraft.patch.liteloader

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.gradle.api.Project
import org.gradle.api.artifacts.Dependency
import xyz.wagyourtail.unimined.api.minecraft.patch.liteloader.LiteLoaderPatcher
import xyz.wagyourtail.unimined.api.runs.RunConfig
import xyz.wagyourtail.unimined.api.unimined
import xyz.wagyourtail.unimined.internal.minecraft.MinecraftProvider
import xyz.wagyourtail.unimined.internal.minecraft.patch.jarmod.JarModMinecraftTransformer
import xyz.wagyourtail.unimined.util.cachingDownload
import kotlin.io.path.reader

class LiteLoaderMinecraftTransformer(
    project: Project,
    provider: MinecraftProvider,
) : JarModMinecraftTransformer(project, provider, providerName = "LiteLoader"), LiteLoaderPatcher {

    private var liteloader: Dependency? = null

    override fun loader(dep: Any, action: Dependency.() -> Unit) {
        liteloader = (if (dep is String && !dep.contains(":")) {
            project.dependencies.create("com.mumfrey:liteloader:$dep")
        } else project.dependencies.create(dep)).apply(action)
    }

    val versions: JsonObject? by lazy {
        val file = project.cachingDownload("https://dl.liteloader.com/versions/versions.json")
        val json = file.reader().use { JsonParser.parseReader(it) }.asJsonObject.getAsJsonObject("versions")

        json.getAsJsonObject(provider.version)
    }

    init {
        project.unimined.legacyLiteloaderMaven()
        project.unimined.liteloaderMaven()
        project.unimined.spongeMaven()
    }

    var tweakClass: String? = null

    override fun apply() {
        if (liteloader == null) {
            loader(provider.version + "-SNAPSHOT")
        }

        val version = liteloader?.version ?: error("liteloader version not set")

        if (versions != null) {
            provider.mods.modImplementation.dependencies.add(liteloader)

            val versions = if (version.endsWith("SNAPSHOT")) {
                val versions = versions!!["snapshots"].asJsonObject
                versions
            } else {
                versions!!["artefacts"].asJsonObject
            }
            versions.getAsJsonArray("libraries")?.let { addLibraries(it) }
            for ((key, value) in versions.getAsJsonObject("com.mumfrey:liteloader").asJsonObject.entrySet()) {
                if (value.asJsonObject["version"].asString == version) {
                    value.asJsonObject.getAsJsonArray("libraries")?.let { addLibraries(it) }
                    val tweakClass = value.asJsonObject["tweakClass"]?.asString
                    if (tweakClass != null) {
                        this.tweakClass = tweakClass
                        return
                    }
                }
            }

            throw IllegalStateException("failed to find liteloader version: $version")
        } else {
            jarModConfiguration.dependencies.add(liteloader)
        }
    }

    fun addLibraries(libraries: JsonArray) {
        for (library in libraries) {
            provider.minecraftLibraries.dependencies.add(
                project.dependencies.create(
                    library.asJsonObject["name"].asString
                )
            )
        }
    }

    override fun applyClientRunTransform(config: RunConfig) {
        super.applyClientRunTransform(config)
        if (tweakClass != null) {
            config.mainClass.set("net.minecraft.launchwrapper.Launch")
            config.args(
                "--tweakClass",
                tweakClass!!
            )
        }
    }


}