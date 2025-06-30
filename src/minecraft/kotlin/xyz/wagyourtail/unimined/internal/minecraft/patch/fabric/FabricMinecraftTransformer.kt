package xyz.wagyourtail.unimined.internal.minecraft.patch.fabric

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.gradle.api.Project
import xyz.wagyourtail.unimined.api.mapping.task.ExportMappingsTask
import xyz.wagyourtail.unimined.api.runs.RunConfig
import xyz.wagyourtail.unimined.api.unimined
import xyz.wagyourtail.unimined.internal.minecraft.MinecraftProvider
import xyz.wagyourtail.unimined.api.minecraft.MinecraftJar
import xyz.wagyourtail.unimined.api.minecraft.task.AbstractRemapJarTask
import xyz.wagyourtail.unimined.api.minecraft.task.RemapJarTask
import xyz.wagyourtail.unimined.internal.minecraft.patch.conversion.AbstractTotalConversionMinecraftProvider
import xyz.wagyourtail.unimined.util.FinalizeOnRead
import xyz.wagyourtail.unimined.util.LazyMutable
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
    override var canCombine: Boolean by FinalizeOnRead(LazyMutable {
        super.canCombine || SemVerUtils.matches(fabricDep.version!!, ">=0.16.0")
    })

    override val ENVIRONMENT: String = "Lnet/fabricmc/api/Environment;"
    override val ENV_TYPE: String = "Lnet/fabricmc/api/EnvType;"

    override fun addMavens() {
        project.unimined.fabricMaven()
    }

    override fun merge(clientjar: MinecraftJar, serverjar: MinecraftJar): MinecraftJar {
        if (provider.canCombine) {
            // Game supports merging without processing
            return super.merge(clientjar, serverjar)
        } else if (this.canCombine) {
            // The JARs can be combined after mapping and Fabric will load them
            val intermediary = prodNamespace
            val client = provider.mappings.checkedNsOrNull("clientOfficial")
                ?: provider.mappings.checkedNs("client")
            val server = provider.mappings.checkedNsOrNull("serverOfficial")
                ?: provider.mappings.checkedNs("server")
            val clientJarFixed = MinecraftJar(
                clientjar.parentPath,
                clientjar.name,
                clientjar.envType,
                clientjar.version,
                clientjar.patches,
                client,
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
                server,
                serverjar.awOrAt,
                serverjar.extension,
                serverjar.path
            )
            return super.internalMerge(
                provider.minecraftRemapper.provide(clientJarFixed, intermediary),
                provider.minecraftRemapper.provide(serverJarFixed, intermediary),
            )
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

    override fun applyClientRunTransform(config: RunConfig) {
        super.applyClientRunTransform(config)
        this.applyRunTransform(config)
    }

    override fun applyServerRunTransform(config: RunConfig) {
        super.applyServerRunTransform(config)
        this.applyRunTransform(config)
    }

    open fun applyRunTransform(config: RunConfig) {
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
        if (customGameProvider) config.jvmArgs("-Dfabric.skipMcProvider")
        if (provider is AbstractTotalConversionMinecraftProvider) {
            config.jvmArgs("-Dfabric.gameVersion=${provider.baseVersion}")
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
        val targetNs = if (
            provider.mappings.checkedNsOrNull("clientOfficial") != null ||
            provider.mappings.checkedNsOrNull("serverOfficial") != null ||
            provider.mappings.checkedNsOrNull("client") != null ||
            provider.mappings.checkedNsOrNull("server") != null
        ) {
            setOf(
                provider.mappings.checkedNsOrNull("clientOfficial") ?: provider.mappings.checkedNs("client"),
                provider.mappings.checkedNsOrNull("serverOfficial") ?: provider.mappings.checkedNs("server"),
                provider.mappings.devNamespace
            )
        } else {
            setOf(prodNamespace, provider.mappings.devNamespace)
        }

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