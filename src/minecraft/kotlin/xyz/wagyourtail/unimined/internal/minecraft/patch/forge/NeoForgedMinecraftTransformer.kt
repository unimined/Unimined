package xyz.wagyourtail.unimined.internal.minecraft.patch.forge

import com.google.gson.JsonObject
import kotlinx.coroutines.runBlocking
import org.gradle.api.Project
import org.gradle.api.artifacts.Dependency
import xyz.wagyourtail.unimined.api.minecraft.MinecraftJar
import xyz.wagyourtail.unimined.api.minecraft.patch.forge.NeoForgedPatcher
import xyz.wagyourtail.unimined.api.minecraft.task.AbstractRemapJarTask
import xyz.wagyourtail.unimined.api.minecraft.task.RemapJarTask
import xyz.wagyourtail.unimined.api.unimined
import xyz.wagyourtail.unimined.internal.minecraft.MinecraftProvider
import xyz.wagyourtail.unimined.internal.minecraft.patch.forge.fg3.FG3MinecraftTransformer
import xyz.wagyourtail.unimined.internal.minecraft.patch.jarmod.JarModMinecraftTransformer
import xyz.wagyourtail.unimined.internal.minecraft.resolver.parseAllLibraries
import xyz.wagyourtail.unimined.mapping.EnvType
import xyz.wagyourtail.unimined.mapping.jvms.four.two.one.InternalName
import xyz.wagyourtail.unimined.util.FinalizeOnWrite
import xyz.wagyourtail.unimined.util.MustSet
import xyz.wagyourtail.unimined.util.SemVerUtils
import xyz.wagyourtail.unimined.util.classifier
import xyz.wagyourtail.unimined.util.readZipContents
import java.nio.file.Path
import java.util.jar.Attributes
import java.util.jar.Manifest
import kotlin.collections.set
import kotlin.io.path.createDirectories
import kotlin.io.path.outputStream

open class NeoForgedMinecraftTransformer(project: Project, provider: MinecraftProvider) : ForgeLikeMinecraftTransformer(project, provider, "NeoForged"),
    NeoForgedPatcher<JarModMinecraftTransformer> {

    override var forgeTransformer: JarModMinecraftTransformer by FinalizeOnWrite(MustSet())

    override fun addMavens() {
        project.unimined.neoForgedMaven()
    }

    override fun loader(dep: Any, action: Dependency.() -> Unit) {
        forge.dependencies.add(if ((dep is String && !dep.contains(":")) || dep is Int) {
            if (provider.version == "1.20.1") {
                project.dependencies.create("net.neoforged:forge:${provider.version}-$dep:universal")
            } else {
                var version = provider.version.removePrefix("1.")

                if (provider.minecraftData.mcVersionCompare(provider.version, "26.1") >= 0) {
                    version = "${provider.version}.0"
                } else if (!version.contains(".")) {
                    version = "$version.0"
                }
                project.dependencies.create("net.neoforged:neoforge:$version.$dep:universal")
            }
        } else {
            project.dependencies.create(dep)
        }.apply(action))

        if (forge.dependencies.isEmpty()) {
            throw IllegalStateException("No forge dependency found!")
        }

        if (forge.dependencies.size > 1) {
            throw IllegalStateException("Multiple forge dependencies found, make sure you only have one forge dependency!")
        }

        val forgeDep = forge.dependencies.first()

        if (forgeDep.group != "net.neoforged" || (forgeDep.name != "forge" && forgeDep.name != "neoforge")) {
            throw IllegalStateException("Invalid forge dependency found, if you are using multiple dependencies in the forge configuration, make sure the last one is the forge dependency!")
        }

        forgeTransformer = NeoForgedTransformer(project, this)
    }

    override fun parseVersionJson(json: JsonObject) {
        val libraries = parseAllLibraries(json.getAsJsonArray("libraries"))
        mainClass = json.get("mainClass").asString
        val args = json.get("minecraftArguments").asString
        provider.addLibraries(libraries.filter { !it.name.startsWith("net.neoforged:forge:") || !it.name.startsWith("net.neoforged:neoforge:") })
        tweakClassClient = args.split("--tweakClass")[1].trim().substringBefore(" ")
    }

    override fun configureRemapJar(task: AbstractRemapJarTask) {
        val forgeDep = forge.dependencies.first()
        if (provider.version != "1.20.1") {
            project.logger.info("setting `disableRefmap()` in mixinRemap")
            if (task is RemapJarTask) {
                task.mixinRemap {
                    if (SemVerUtils.matches(forgeDep.version!!.substringBefore("-"), ">=20.2.84")) {
                        enableMixinExtra()
                    }
                    disableRefmap()
                }
            }
        }
    }

    class NeoForgedTransformer(project: Project, parent: ForgeLikeMinecraftTransformer) : FG3MinecraftTransformer(project, parent) {
        override fun writeClientExtraManifest(manifestFile: Path, baseMinecraftClient: MinecraftJar, baseMinecraftServer: MinecraftJar?) = runBlocking {
            // Neoforge requires Minecraft-Dists to be present in client-extra manifest from 1.21.7

            if (SemVerUtils.matches(provider.version, "<1.21.7")) {
                return@runBlocking
            }

            manifestFile.parent.createDirectories()

            val manifest = Manifest()
            manifest.mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
            manifest.mainAttributes[Attributes.Name("Minecraft-Dists")] = provider.side.classifier ?: "client server"

            if (provider.side == EnvType.JOINED && baseMinecraftServer != null) {
                val mappings = parent.provider.mappings.resolve()
                val officialNamespace = parent.provider.mappings.checkedNs("official")
                val namedNamespace = provider.mappings.devNamespace

                val clientEntries = baseMinecraftClient.path.readZipContents().toSet()
                val serverEntries = baseMinecraftServer.path.readZipContents().toSet()

                fun mapEntry(name: String): String {
                    if (!name.endsWith(".class")) {
                        return name
                    }

                    return mappings.map(officialNamespace, namedNamespace, InternalName.unchecked(name.substring(0, name.length - 6))).value + ".class"
                }

                for (clientEntry in clientEntries) {
                    if (clientEntry !in serverEntries) {
                        val attributes = Attributes()
                        attributes[Attributes.Name("Minecraft-Dist")] = "client"
                        manifest.entries[mapEntry(clientEntry)] = attributes
                    }
                }

                for (serverEntry in serverEntries) {
                    if (serverEntry !in clientEntries) {
                        val attributes = Attributes()
                        attributes["Minecraft-Dist"] = "server"
                        manifest.entries[mapEntry(serverEntry)] = attributes
                    }
                }
            }

            manifestFile.outputStream().use(manifest::write)
        }
    }

}
