package xyz.wagyourtail.unimined.internal.minecraft.patch.fabric

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.gradle.api.Project
import org.gradle.api.artifacts.ResolvedArtifact
import xyz.wagyourtail.unimined.api.mapping.task.ExportMappingsTask
import xyz.wagyourtail.unimined.api.runs.RunConfig
import xyz.wagyourtail.unimined.api.unimined
import xyz.wagyourtail.unimined.internal.minecraft.MinecraftProvider
import xyz.wagyourtail.unimined.api.minecraft.MinecraftJar
import xyz.wagyourtail.unimined.api.minecraft.task.AbstractRemapJarTask
import xyz.wagyourtail.unimined.api.minecraft.task.RemapJarTask
import xyz.wagyourtail.unimined.internal.minecraft.patch.reindev.ReIndevProvider
import xyz.wagyourtail.unimined.util.SemVerUtils
import java.io.InputStreamReader
import java.nio.file.Files
import kotlin.io.path.absolutePathString

abstract class FabricMinecraftTransformer(
    project: Project,
    provider: MinecraftProvider
): FabricLikeMinecraftTransformer(
    project,
    provider,
    "fabric",
    "fabric.mod.json",
    "accessWidener"
) {

    override val ENVIRONMENT: String = "Lnet/fabricmc/api/Environment;"
    override val ENV_TYPE: String = "Lnet/fabricmc/api/EnvType;"

    override fun addMavens() {
        project.unimined.fabricMaven()
    }

    override fun merge(clientjar: MinecraftJar, serverjar: MinecraftJar): MinecraftJar {
        if (provider.minecraftData.mcVersionCompare(provider.version, "1.3") > -1) {
            return super.merge(clientjar, serverjar)
        } else if (this is BabricMinecraftTransformer || SemVerUtils.matches(fabricDep.version!!, ">=0.16.0")) {
            val INTERMEDIARY = prodNamespace
            val CLIENT = if (this is BabricMinecraftTransformer) {
                provider.mappings.checkedNsOrNull("clientOfficial") ?: provider.mappings.checkedNs("client")
            } else {
                provider.mappings.checkedNs("clientOfficial")
            }
            val SERVER = if (this is BabricMinecraftTransformer) {
                provider.mappings.checkedNsOrNull("serverOfficial") ?: provider.mappings.checkedNs("server")
            } else {
                provider.mappings.checkedNs("serverOfficial")
            }
            val clientJarFixed = MinecraftJar(
                clientjar.parentPath,
                clientjar.name,
                clientjar.envType,
                clientjar.version,
                clientjar.patches,
                CLIENT,
                clientjar.awOrAt,
                clientjar.extension,
                clientjar.path
            )
            val serverJarFixed = MinecraftJar(
                serverjar.parentPath,
                serverjar.name,
                serverjar.envType,
                serverjar.version,
                serverjar.patches,
                SERVER,
                serverjar.awOrAt,
                serverjar.extension,
                serverjar.path
            )
            val intermediaryClientJar = provider.minecraftRemapper.provide(clientJarFixed, INTERMEDIARY)
            val intermediaryServerJar = provider.minecraftRemapper.provide(serverJarFixed, INTERMEDIARY)
            return super.internalMerge(intermediaryClientJar, intermediaryServerJar)
        }
        throw UnsupportedOperationException("Merging is not supported for this version")
    }


    override fun addIncludeToModJson(json: JsonObject, path: String) {
        var jars = json.get("jars")?.asJsonArray
        if (jars == null) {
            jars = JsonArray()
            json.add("jars", jars)
        }
        jars.add(JsonObject().apply {
            addProperty("file", path)
        })
    }

    override fun applyExtraLaunches() {
        super.applyExtraLaunches()
    }

    override fun applyClientRunTransform(config: RunConfig) {
        super.applyClientRunTransform(config)
        config.properties["intermediaryClasspath"] = {
            intermediaryClasspath.absolutePathString()
        }
        config.properties["classPathGroups"] = {
            groups
        }
        config.jvmArgs(
            "-Dfabric.development=true",
            "-Dfabric.remapClasspathFile=\${intermediaryClasspath}",
            "-Dfabric.classPathGroups=\${classPathGroups}"
        )
        if (provider is ReIndevProvider) {
            config.jvmArgs("-Dfabric.gameVersion=b1.7.3")
        }
    }

    override fun applyServerRunTransform(config: RunConfig) {
        super.applyServerRunTransform(config)
        config.properties["intermediaryClasspath"] = {
            intermediaryClasspath.absolutePathString()
        }
        config.properties["classPathGroups"] = {
            groups
        }
        config.jvmArgs(
            "-Dfabric.development=true",
            "-Dfabric.remapClasspathFile=\${intermediaryClasspath}",
            "-Dfabric.classPathGroups=\${classPathGroups}"
        )
        if (provider is ReIndevProvider) {
            config.jvmArgs("-Dfabric.gameVersion=b1.7.3")
        }
    }

    override fun collectInterfaceInjections(baseMinecraft: MinecraftJar, injections: HashMap<String, List<String>>) {
        val modJsonPath = this.getModJsonPath()

        if (modJsonPath != null && modJsonPath.exists()) {
            val json = JsonParser.parseReader(InputStreamReader(Files.newInputStream(modJsonPath.toPath()))).asJsonObject

            val custom = json.getAsJsonObject("custom")

            if (custom != null) {
                val interfaces = custom.getAsJsonObject("loom:injected_interfaces")

                if (interfaces != null) {
                    collectInterfaceInjections(baseMinecraft, injections, interfaces)
                }
            }
        }
    }

    override fun configureRemapJar(task: AbstractRemapJarTask) {
        task.manifest {
            it.attributes(mapOf(
                "Fabric-Minecraft-Version" to provider.version,
                "Fabric-Loader-Version" to fabricDep.version,
            ))
        }
        if (fabricDep.version?.let { SemVerUtils.matches(it, ">=0.15.0") } == true) {
            project.logger.info("enabling mixin extra")
            if (task is RemapJarTask) {
                task.mixinRemap {
                    enableMixinExtra()
                }
            }
        }

        additionalRemapJarConfiguration(task)
    }

    open fun additionalRemapJarConfiguration(task: AbstractRemapJarTask) {}

    override fun configureRuntimeMappings(export: ExportMappingsTask.Export) {
        val targetNs = {
            if (provider.mappings.checkedNsOrNull("clientOfficial") != null ||
                provider.mappings.checkedNsOrNull("serverOfficial") != null ||
                provider.mappings.checkedNsOrNull("client") != null ||
                provider.mappings.checkedNsOrNull("server") != null) {
                setOf(
                    provider.mappings.checkedNsOrNull("clientOfficial") ?: provider.mappings.checkedNs("client"),
                    provider.mappings.checkedNsOrNull("serverOfficial") ?: provider.mappings.checkedNs("server"),
                    provider.mappings.devNamespace
                )
            } else {
                setOf(prodNamespace, provider.mappings.devNamespace)
            }
        }.invoke()

        val sourceNs = if (targetNs.contains(prodNamespace)) {
            provider.mappings.checkedNs("official")
        } else {
            prodNamespace
        }

        export.sourceNamespace = sourceNs
        export.targetNamespace = targetNs
        export.renameNs[prodNamespace] = "intermediary"
        export.renameNs[provider.mappings.devNamespace] = "named"
    }
}