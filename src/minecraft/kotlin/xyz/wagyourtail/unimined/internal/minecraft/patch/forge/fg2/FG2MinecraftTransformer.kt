package xyz.wagyourtail.unimined.internal.minecraft.patch.forge.fg2

import org.gradle.api.Project
import xyz.wagyourtail.unimined.api.minecraft.MinecraftJar
import xyz.wagyourtail.unimined.api.runs.RunConfig
import xyz.wagyourtail.unimined.api.unimined
import xyz.wagyourtail.unimined.internal.minecraft.patch.forge.ForgeLikeMinecraftTransformer
import xyz.wagyourtail.unimined.internal.minecraft.patch.jarmod.JarModMinecraftTransformer
import xyz.wagyourtail.unimined.internal.minecraft.transform.fixes.FixFG2Coremods
import xyz.wagyourtail.unimined.internal.minecraft.transform.fixes.FixFG2ResourceLoading
import xyz.wagyourtail.unimined.internal.minecraft.transform.merge.ClassMerger
import xyz.wagyourtail.unimined.mapping.EnvType
import xyz.wagyourtail.unimined.mapping.Namespace
import xyz.wagyourtail.unimined.util.*
import xyz.wagyourtail.unimined.util.deleteRecursively
import xyz.wagyourtail.unimined.util.readZipContents
import xyz.wagyourtail.unimined.util.readZipInputStreamFor
import java.io.File
import java.net.URI
import java.nio.file.*
import kotlin.io.path.*

open class FG2MinecraftTransformer(project: Project, val parent: ForgeLikeMinecraftTransformer): JarModMinecraftTransformer(
    project,
    parent.provider,
    jarModProvider = "forge",
    providerName = "${parent.providerName}-FG2"
) {
    init {
        project.logger.lifecycle("[Unimined/Forge] Using FG2 transformer")
        parent.accessTransformerPaths = listOf("forge_at.cfg", "fml_at.cfg")
    }

    override fun defaultProdNamespace() = provider.mappings.checkedNs("searge")

    override val merger: ClassMerger
        get() = parent.merger

    override fun merge(clientjar: MinecraftJar, serverjar: MinecraftJar): MinecraftJar {
        val clientPatched = transformIntern(clientjar)
        val serverPatched = transformIntern(serverjar)
        return super.merge(clientPatched, serverPatched)
    }

    override fun beforeMappingsResolve() {
        // get and add forge-src to mappings
        val forgeDep = parent.forge.dependencies.last()
        provider.mappings {
            if (provider.minecraftData.mcVersionCompare(provider.version, "1.7.10") != -1 && !parent.customSearge) {
                searge()
            }
            if (provider.minecraftData.mcVersionCompare(provider.version, "1.7") == -1 && !parent.customSearge) {
                forgeBuiltinMCP(forgeDep.version!!.substringAfter("${provider.version}-"))
            }
        }
    }

    override val transform = (listOf<(FileSystem) -> Unit>(
        FixFG2Coremods::fixCoremods,
        FixFG2ResourceLoading::fixResourceLoading,
    ) + super.transform).toMutableList()

    override fun apply() {
        super.apply()
    }

    override fun transform(minecraft: MinecraftJar): MinecraftJar {
        project.logger.lifecycle("[Unimined/Forge] transforming minecraft jar for FG2")
        project.logger.info("minecraft: $minecraft")

        val shadedForge = super.transform(
            if (minecraft.envType == EnvType.JOINED) minecraft else transformIntern(
                minecraft
            )
        )
        return provider.minecraftRemapper.provide(shadedForge, provider.mappings.checkedNs("searge"))
    }

    private fun transformIntern(minecraft: MinecraftJar): MinecraftJar {
        val forgeUniversal = parent.forge.dependencies.last()
        val forgeJar = parent.forge.getFiles(forgeUniversal) { it.extension == "zip" || it.extension == "jar" }.singleFile

        val outFolder = minecraft.path.parent.resolve(providerName).resolve("${forgeUniversal.version}")
            .createDirectories()

        val patchedMC = MinecraftJar(
            minecraft,
            name = "forge",
            parentPath = outFolder,
        )

        // apply binary patches
        val binaryPatchFile = forgeJar.toPath().readZipInputStreamFor("binpatches.pack.lzma") {
            outFolder.resolve("binpatches.pack.lzma")
                .apply {
                    writeBytes(
                        it.readBytes(),
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING
                    )
                }
        }

        if (!patchedMC.path.exists() || project.unimined.forceReload) {
            patchedMC.path.deleteIfExists()
            applyBinPatches(minecraft, binaryPatchFile, patchedMC)
        }
        return patchedMC
    }

    open fun applyBinPatches(minecraft: MinecraftJar, binaryPatchFile: Path, patchedMC: MinecraftJar) {
        FG2TaskApplyBinPatches(project).doTask(
            minecraft.path.toFile(),
            binaryPatchFile.toFile(),
            patchedMC.path.toFile(),
            if (minecraft.envType == EnvType.SERVER) "server" else "client"
        )
    }

    override fun applyClientRunTransform(config: RunConfig) {
        super.applyClientRunTransform(config)

        config.properties["source_roots"] = {
            parent.groups
        }

        val forgeUniversal = parent.forge.dependencies.last()
        val forgeJar = parent.forge.getFiles(forgeUniversal) { it.extension == "zip" || it.extension == "jar" }.singleFile

        val contents = forgeJar.toPath().readZipContents()

        // test if fmltweaker is in cpw or net.minecraftforge
        val tweakClassClient = parent.tweakClassClient ?: if (contents.contains("cpw/mods/fml/common/launcher/FMLTweaker.class")) {
            "cpw.mods.fml.common.launcher.FMLTweaker"
        } else if (contents.contains("net/minecraftforge/fml/common/launcher/FMLTweaker.class")) {
            "net.minecraftforge.fml.common.launcher.FMLTweaker"
        } else {
            null
        }
        if (tweakClassClient != null) {
            config.args("--tweakClass",
                tweakClassClient
            )
        }

        parent.mainClass?.let {
            config.mainClass.set(it)
        }
        config.jvmArgs(
            "-Dfml.ignoreInvalidMinecraftCertificates=true",
            "-Dfml.deobfuscatedEnvironment=true",
            "-Dnet.minecraftforge.gradle.GradleStart.srg.srg-mcp=${parent.srgToMCPAsSRG}"
        )
        config.environment["MOD_CLASSES"] = "\${source_roots}"
    }

    override fun applyServerRunTransform(config: RunConfig) {
        super.applyServerRunTransform(config)

        config.properties["source_roots"] = {
            parent.groups
        }

        val forgeUniversal = parent.forge.dependencies.last()
        val forgeJar = parent.forge.getFiles(forgeUniversal) { it.extension == "zip" || it.extension == "jar" }.singleFile

        val contents = forgeJar.toPath().readZipContents()

        // test if fmltweaker is in cpw or net.minecraftforge
        val tweakClassServer = parent.tweakClassServer ?: if (contents.contains("cpw/mods/fml/common/launcher/FMLServerTweaker.class")) {
            "cpw.mods.fml.common.launcher.FMLServerTweaker"
        } else if (contents.contains("net/minecraftforge/fml/common/launcher/FMLServerTweaker.class")) {
            "net.minecraftforge.fml.common.launcher.FMLServerTweaker"
        } else {
            null
        }

        if (tweakClassServer != null) {
            config.args(
                "--tweakClass",
                tweakClassServer
            )
        }
        parent.mainClass?.let {
            config.mainClass.set(it)
        }
        config.jvmArgs(
            "-Dfml.ignoreInvalidMinecraftCertificates=true",
            "-Dfml.deobfuscatedEnvironment=true",
            "-Dnet.minecraftforge.gradle.GradleStart.srg.srg-mcp=${parent.srgToMCPAsSRG}"
        )
        config.environment["MOD_CLASSES"] = "\${source_roots}"
    }

    override fun afterRemap(baseMinecraft: MinecraftJar): MinecraftJar {
        return fixForge(baseMinecraft)
    }

    private fun fixForge(baseMinecraft: MinecraftJar): MinecraftJar {
        if (!baseMinecraft.patches.contains("fixForge") && baseMinecraft.mappingNamespace != provider.mappings.checkedNs("official")) {
            val target = MinecraftJar(
                baseMinecraft,
                patches = baseMinecraft.patches + "fixForge",
            )

            if (target.path.exists() && !project.unimined.forceReload) {
                return target
            }

            Files.copy(baseMinecraft.path, target.path, StandardCopyOption.REPLACE_EXISTING)
            val mc = URI.create("jar:${target.path.toUri()}")
            try {
                FileSystems.newFileSystem(mc, mapOf("mutable" to true), null).use { out ->
                    out.getPath("argo").apply {
                        if (exists()) {
                            deleteRecursively()
                        }
                    }
                    out.getPath("org/bouncycastle").apply {
                        if (exists()) {
                            deleteRecursively()
                        }
                    }
                    out.getPath("com/google").apply {
                        if (exists()) {
                            deleteRecursively()
                        }
                    }
                    out.getPath("org/apache").apply {
                        if (exists()) {
                            deleteRecursively()
                        }
                    }

                    out.getPath("binpatches.pack.lzma").deleteIfExists()
                }
            } catch (e: Throwable) {
                target.path.deleteIfExists()
                throw e
            }
            return target
        }
        return baseMinecraft
    }


}