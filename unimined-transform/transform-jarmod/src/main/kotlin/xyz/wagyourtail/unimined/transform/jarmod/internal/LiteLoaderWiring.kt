package xyz.wagyourtail.unimined.transform.jarmod.internal

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.gradle.api.Project
import org.gradle.api.GradleException
import xyz.wagyourtail.unimined.api.spec.Side
import xyz.wagyourtail.unimined.api.util.UniminedCache
import xyz.wagyourtail.unimined.minecraft.MinecraftEnvironment
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * LiteLoader env wiring — 1.x `LiteLoaderMinecraftTransformer.apply()`
 * parity. Resolves `dl.liteloader.com/versions/versions.json` for the
 * env's MC version (cached under `UniminedCache.global` — honors
 * --offline/--refresh-dependencies like the fmllib store):
 * `artefacts`/`snapshots` libraries land on `minecraftLibraries`, the
 * `com.mumfrey:liteloader` entry for the declared version contributes
 * its `libraries` + `tweakClass` (`--tweakClass` program arg on runs).
 * MC versions genuinely absent from versions.json fall back to merging
 * the artifact via `jarMod`; a fetch failure with no cache is a hard
 * error, never the merge path.
 */
internal object LiteLoaderWiring {

    private const val VERSIONS_URL =
        "https://dl.liteloader.com/versions/versions.json"

    fun wire(project: Project, environment: MinecraftEnvironment) {
        // liteloader is client-only — default the env side before the
        // guard reads it; an explicit `side =` still trips the check
        environment.side.convention(Side.CLIENT)
        if (environment.side.get() != Side.CLIENT) {
            throw GradleException(
                "liteloader only supports CLIENT environments " +
                        "(side=${environment.side.get()})"
            )
        }
        val mcVersion = environment.version.get()
        val declaredVersion = environment.spec().loader?.version
            ?: "$mcVersion-SNAPSHOT"

        val versionEntry = fetchVersions(project, mcVersion)
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
                    // tweakers need LaunchWrapper's main, not the
                    // version-json default (`net.minecraft.client.main.Main`)
                    config.mainClass = "net.minecraft.launchwrapper.Launch"
                    config.programArgs += listOf("--tweakClass", tweakClass)
                }
        }
    }

    /**
     * versions.json is needed at configuration time (it shapes the
     * dependency graph + tweakClass), so it can't ride a task — cached
     * under the unimined global cache instead, honoring --offline and
     * --refresh-dependencies like the fmllib/loader-artifact stores.
     * A fetch failure with no cache throws rather than silently taking
     * the versions.json-unknown jarMod path; `null` now means the MC
     * version is genuinely absent from a successfully-read file.
     */
    private fun fetchVersions(project: Project, mcVersion: String): JsonObject? {
        val dest = UniminedCache.global(project)
            .resolve("liteloader/versions.json")
        val refresh = project.gradle.startParameter.isRefreshDependencies
        val have = Files.exists(dest)
        if (!have || refresh) {
            if (project.gradle.startParameter.isOffline) {
                if (!have) throw GradleException(
                    "offline and no cached liteloader versions.json " +
                            "under $dest")
            } else {
                try {
                    Files.createDirectories(dest.parent)
                    URI.create(VERSIONS_URL).toURL().openStream().use {
                        Files.copy(it, dest,
                            StandardCopyOption.REPLACE_EXISTING)
                    }
                } catch (e: Exception) {
                    if (!have) throw GradleException(
                        "could not fetch liteloader versions.json " +
                                "from $VERSIONS_URL", e)
                    // stale cache beats failing on a flaky network
                }
            }
        }
        val root = kotlinx.serialization.json.Json
            .parseToJsonElement(dest.toFile().readText()).jsonObject
        return root["versions"]?.jsonObject?.get(mcVersion)?.jsonObject
    }
}
