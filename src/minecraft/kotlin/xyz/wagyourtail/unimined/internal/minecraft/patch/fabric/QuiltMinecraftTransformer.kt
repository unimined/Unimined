package xyz.wagyourtail.unimined.internal.minecraft.patch.fabric

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.gradle.api.Project
import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.ResolvedArtifact
import xyz.wagyourtail.unimined.api.runs.RunConfig
import xyz.wagyourtail.unimined.api.unimined
import xyz.wagyourtail.unimined.internal.minecraft.MinecraftProvider
import xyz.wagyourtail.unimined.api.minecraft.MinecraftJar
import java.io.InputStreamReader
import java.nio.file.Files
import kotlin.io.path.absolutePathString

open class QuiltMinecraftTransformer(
    project: Project,
    provider: MinecraftProvider
): FabricLikeMinecraftTransformer(
    project,
    provider,
    "quilt",
    "quilt.mod.json",
    "access_widener"
) {

    override val ENVIRONMENT: String = "Lnet/fabricmc/api/Environment;"
    override val ENV_TYPE: String = "Lnet/fabricmc/api/EnvType;"

    override val defaultProdNamespace: String = "intermediary"

    override fun addIntermediaryMappings() {
        provider.mappings {
            intermediary()
        }
    }

    override fun collectInterfaceInjections(baseMinecraft: MinecraftJar, injections: HashMap<String, List<String>>) {
        val modJsonPath = this.getModJsonPath()

        if (modJsonPath != null && modJsonPath.exists()) {
            val json = JsonParser.parseReader(InputStreamReader(Files.newInputStream(modJsonPath.toPath()))).asJsonObject

            val custom = json.getAsJsonObject("custom")

            if (custom != null) {
                val quiltLoom = custom.getAsJsonObject("quilt_loom")

                if (quiltLoom != null) {
                    val interfaces = quiltLoom.getAsJsonObject("injected_interfaces")

                    if (interfaces != null) collectInterfaceInjections(baseMinecraft, injections, interfaces)
                }
            }

            val quiltLoom = json.getAsJsonObject("quilt_loom")

            if (quiltLoom != null) {
                val interfaces = quiltLoom.getAsJsonObject("injected_interfaces")

                if (interfaces != null) collectInterfaceInjections(baseMinecraft, injections, interfaces)
            }
        }
    }

    override fun loader(dep: Any, action: Dependency.() -> Unit) {
        fabric.dependencies.add(
            (if (dep is String && !dep.contains(":")) {
                project.dependencies.create("org.quiltmc:quilt-loader:$dep")
            } else project.dependencies.create(dep)).apply(action)
        )
    }

    override fun addMavens() {
        project.unimined.quiltMaven()
        project.unimined.fabricMaven()
    }

    override fun addIncludeToModJson(json: JsonObject, path: String) {
        val quilt_loader = json.get("quilt_loader").asJsonObject
        var jars = quilt_loader.get("jars")?.asJsonArray
        if (jars == null) {
            jars = JsonArray()
            quilt_loader.add("jars", jars)
        }

        jars.add(path)
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
            "-Dloader.development=true",
            "-Dloader.remapClasspathFile=\${intermediaryClasspath}",
            "-Dloader.classPathGroups=\${classPathGroups}"
        )
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
            "-Dloader.development=true",
            "-Dloader.remapClasspathFile=\${intermediaryClasspath}",
            "-Dloader.classPathGroups=\${classPathGroups}"
        )
    }


}