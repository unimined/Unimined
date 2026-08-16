package xyz.wagyourtail.unimined.internal.minecraft.patch.fabric

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.gradle.api.Project
import xyz.wagyourtail.unimined.api.minecraft.patch.fabric.OrnithePatcher
import xyz.wagyourtail.unimined.api.minecraft.task.AbstractRemapJarTask
import xyz.wagyourtail.unimined.api.unimined
import xyz.wagyourtail.unimined.internal.minecraft.MinecraftProvider
import xyz.wagyourtail.unimined.internal.minecraft.resolver.Library
import xyz.wagyourtail.unimined.util.FinalizeOnRead
import xyz.wagyourtail.unimined.util.cachingDownload
import xyz.wagyourtail.unimined.util.defaultedMapOf
import java.io.InputStreamReader
import java.net.MalformedURLException
import java.net.URI
import kotlin.io.path.inputStream

open class OrnitheFabricMinecraftTransformer(
    project: Project,
    provider: MinecraftProvider
): LegacyFabricMinecraftTransformer(project, provider), OrnithePatcher {

    override val defaultProdNamespace: String = "calamus"

    internal var applyLibraryPatching: Boolean by FinalizeOnRead(false)

    internal val libraryPatchArray = defaultedMapOf<Pair<Int, String>, JsonArray> { key ->
        project.cachingDownload(
            URI.create(getLibraryPatchUrl(key.first, key.second))
        ).inputStream().use {
            JsonParser.parseReader(InputStreamReader(it)).asJsonArray
        }
    }

    override fun additionalRemapJarConfiguration(task: AbstractRemapJarTask) {
        task.manifest {
            it.attributes(mapOf(
                "Calamus-Generation" to provider.mappings.ornitheGenVersion.toString()
            ))
        }
    }

    override fun addMavens() {
        super.addMavens()
        project.unimined.ornitheMaven()
    }

    override fun addIntermediaryMappings() {
        provider.mappings {
            calamus()
        }
    }

    override fun apply() {
        if (applyLibraryPatching) {
            val libraries = libraryPatchArray[Pair(provider.mappings.ornitheGenVersion, provider.version)]
                .asList()
                .map { parseLibraryPatch(it.asJsonObject) }

            provider.addLibraries(libraries)
        }

        super.apply()
    }

    override fun enableLibraryPatching() {
        applyLibraryPatching = true
    }

    internal fun getLibraryPatchUrl(gen: Int, version: String): String {
        return "https://meta.ornithemc.net/v3/versions/gen$gen/libraries/$version"
    }

    internal fun parseLibraryPatch(element: JsonObject): Library {
        return Library(
            null,
            element.get("name").asString,
            try {
                element.get("url")?.asString?.let { URI(it) }
            } catch (e: MalformedURLException) {
                null
            },
            mapOf(),
            null,
            listOf()
        )
    }

}