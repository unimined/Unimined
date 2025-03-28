package xyz.wagyourtail.unimined.internal.minecraft.patch.fabric

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.gradle.api.Project
import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.ResolvedArtifact
import xyz.wagyourtail.unimined.api.minecraft.EnvType
import xyz.wagyourtail.unimined.api.minecraft.MinecraftJar
import xyz.wagyourtail.unimined.api.minecraft.task.AbstractRemapJarTask
import xyz.wagyourtail.unimined.api.runs.RunConfig
import xyz.wagyourtail.unimined.api.unimined
import xyz.wagyourtail.unimined.internal.minecraft.MinecraftProvider
import xyz.wagyourtail.unimined.util.openZipFileSystem
import java.io.InputStreamReader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.io.path.absolutePathString
import kotlin.io.path.exists

open class FlintMinecraftTransformer(
    project: Project,
    provider: MinecraftProvider
): FabricLikeMinecraftTransformer(
    project,
    provider,
    "flint",
    "flintmodule.json",
    "accessWidener"
) {
    override val ENVIRONMENT: String
        get() = throw UnsupportedOperationException("Environment is not supported by flint")

    override val ENV_TYPE: String
        get() = throw UnsupportedOperationException("EnvType is not supported by flint")

    init {
        provider.side = EnvType.CLIENT
    }

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
                val interfaces = custom.getAsJsonObject("steel:injected_interfaces")

                if (interfaces != null) {
                    collectInterfaceInjections(baseMinecraft, injections, interfaces)
                }
            }
        }
    }

    override fun loader(dep: Any, action: Dependency.() -> Unit) {
        fabric.dependencies.add(
            (if (dep is String && !dep.contains(":")) {
                project.dependencies.create("net.flintloader:punch:$dep")
            } else project.dependencies.create(dep)).apply(action)
        )
    }

    override fun addMavens() {
        project.unimined.flintMaven("releases")
        project.unimined.flintMaven("mirror")
    }

    override fun applyExtraLaunches() {
        if (provider.side != EnvType.CLIENT) {
            throw UnsupportedOperationException("Flint only supports client. Current side is ${provider.side}")
        }
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
            "-Dflint.development=true",
            "-Dflint.remapClasspathFile=\${intermediaryClasspath}",
            "-Dflint.classPathGroups=\${classPathGroups}"
        )
    }

    override fun merge(clientjar: MinecraftJar, serverjar: MinecraftJar): MinecraftJar {
        throw UnsupportedOperationException("Merging is not supported on flint")
    }

    override fun afterRemapJarTask(remapJarTask: AbstractRemapJarTask, output: Path) {
        this.insertAccessWidener(output)
    }

    override fun addIncludeToModJson(json: JsonObject, dep: ResolvedArtifact, path: String) {
        throw UnsupportedOperationException("Flint does not support nested modules yet")
    }

    private fun insertAccessWidener(output: Path) {
        if (accessWidener != null) {
            output.openZipFileSystem(mapOf("mutable" to true)).use { fs ->
                val mod = fs.getPath(modJsonName)
                if (!Files.exists(mod)) {
                    throw IllegalStateException("$modJsonName not found in jar")
                }
                val aw = accessWidener!!.toPath()
                var parent = aw.parent
                while (!fs.getPath(parent.relativize(aw).toString()).exists()) {
                    parent = parent.parent
                    if (parent.relativize(aw).toString() == aw.toString()) {
                        throw IllegalStateException("Access widener not found in jar")
                    }
                }
                val awPath = fs.getPath(parent.relativize(aw).toString())
                val json = JsonParser.parseReader(InputStreamReader(Files.newInputStream(mod))).asJsonObject
                json.addProperty(accessWidenerJsonKey, awPath.toString())
                Files.write(mod, GSON.toJson(json).toByteArray(), StandardOpenOption.TRUNCATE_EXISTING)
            }
        }
    }
}