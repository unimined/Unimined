package xyz.wagyourtail.unimined.transform.jarmod.internal

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.gradle.api.Project
import org.gradle.api.GradleException
import xyz.wagyourtail.unimined.api.spec.Side
import xyz.wagyourtail.unimined.minecraft.MinecraftEnvironment
import java.net.URI

/**
 * LiteLoader env wiring — 1.x `LiteLoaderMinecraftTransformer.apply()`
 * parity. Resolves `dl.liteloader.com/versions/versions.json` for the
 * env's MC version: `artefacts`/`snapshots` libraries land on
 * `minecraftLibraries`, the `com.mumfrey:liteloader` entry for the
 * declared version contributes its `libraries` + `tweakClass`
 * (`--tweakClass` program arg on runs). Versions.json-unknown MC
 * versions fall back to merging the artifact via `jarMod`.
 */
internal object LiteLoaderWiring {

    private const val VERSIONS_URL =
        "https://dl.liteloader.com/versions/versions.json"

    fun wire(project: Project, environment: MinecraftEnvironment) {
        if (environment.side.get() != Side.CLIENT) {
            throw GradleException(
                "liteloader only supports CLIENT environments " +
                        "(side=${environment.side.get()})"
            )
        }
        val mcVersion = environment.version.get()
        val declaredVersion = environment.spec().loader?.version
            ?: "$mcVersion-SNAPSHOT"

        val versionEntry = fetchVersions(mcVersion)
        if (versionEntry == null) {
            // unknown to versions.json — treat as a jar merge
            val envId = environment.environmentId
            val jarMod = project.configurations.getByName(
                if (envId == "main") "jarMod" else
                    "jarMod${envId.replaceFirstChar { it.uppercaseChar() }}"
            )
            jarMod.dependencies.add(
                project.dependencies.create("com.mumfrey:liteloader:$declaredVersion")
            )
            return
        }

        // known version: the artifact is a library dep, not a merge
        project.dependencies.add(
            environment.modImplementationConfigurationName,
            "com.mumfrey:liteloader:$declaredVersion"
        )
        val bucket = if (declaredVersion.endsWith("SNAPSHOT")) {
            versionEntry["snapshots"]?.jsonObject
        } else {
            versionEntry["artefacts"]?.jsonObject
        } ?: throw GradleException(
            "liteloader versions.json has no " +
                    "${if (declaredVersion.endsWith("SNAPSHOT")) "snapshots" else "artefacts"} " +
                    "for MC $mcVersion"
        )

        bucket["libraries"]?.jsonArray?.forEach { lib ->
            project.dependencies.add(
                environment.minecraftLibrariesConfigurationName,
                lib.jsonObject["name"]!!.jsonPrimitive.content
            )
        }

        val artifactEntry = bucket["com.mumfrey:liteloader"]?.jsonObject
            ?.entries?.firstOrNull { (_, v) ->
                v.jsonObject["version"]?.jsonPrimitive?.content == declaredVersion
            }?.value?.jsonObject
            ?: throw GradleException(
                "failed to find liteloader version $declaredVersion in " +
                        "versions.json for MC $mcVersion"
            )

        artifactEntry["libraries"]?.jsonArray?.forEach { lib ->
            project.dependencies.add(
                environment.minecraftLibrariesConfigurationName,
                lib.jsonObject["name"]!!.jsonPrimitive.content
            )
        }

        val tweakClass = artifactEntry["tweakClass"]?.jsonPrimitive?.content
        if (tweakClass != null) {
            val transform = project.extensions.getByType(
                xyz.wagyourtail.unimined.transform.TransformExtension::class.java
            )
            transform.sourceSet(environment.environmentId).runMutators +=
                org.gradle.api.Action { config ->
                    config.programArgs += listOf("--tweakClass", tweakClass)
                }
        }
    }

    private fun fetchVersions(mcVersion: String): JsonObject? = try {
        val root = kotlinx.serialization.json.Json
            .parseToJsonElement(
                URI.create(VERSIONS_URL).toURL().readText()
            ).jsonObject
        root["versions"]?.jsonObject?.get(mcVersion)?.jsonObject
    } catch (e: Exception) {
        null
    }
}
