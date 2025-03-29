package xyz.wagyourtail.unimined.internal.minecraft.patch.forge.fg3

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.minecraftforge.binarypatcher.ConsoleTool
import org.apache.commons.io.output.NullOutputStream
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.ExternalDependency
import org.gradle.api.artifacts.ResolvedArtifact
import org.gradle.api.file.FileCollection
import org.gradle.api.logging.LogLevel
import org.jetbrains.annotations.ApiStatus
import org.jetbrains.annotations.VisibleForTesting
import xyz.wagyourtail.unimined.*
import xyz.wagyourtail.unimined.api.minecraft.EnvType
import xyz.wagyourtail.unimined.api.minecraft.MinecraftJar
import xyz.wagyourtail.unimined.api.minecraft.task.AbstractRemapJarTask
import xyz.wagyourtail.unimined.api.runs.RunConfig
import xyz.wagyourtail.unimined.api.unimined
import xyz.wagyourtail.unimined.internal.minecraft.patch.fabric.FabricLikeMinecraftTransformer
import xyz.wagyourtail.unimined.internal.minecraft.patch.forge.ForgeLikeMinecraftTransformer
import xyz.wagyourtail.unimined.internal.minecraft.patch.forge.MinecraftForgeMinecraftTransformer
import xyz.wagyourtail.unimined.internal.minecraft.patch.forge.NeoForgedMinecraftTransformer
import xyz.wagyourtail.unimined.internal.minecraft.patch.forge.fg3.mcpconfig.McpConfigData
import xyz.wagyourtail.unimined.internal.minecraft.patch.forge.fg3.mcpconfig.McpConfigStep
import xyz.wagyourtail.unimined.internal.minecraft.patch.forge.fg3.mcpconfig.McpExecutor
import xyz.wagyourtail.unimined.internal.minecraft.patch.jarmod.JarModMinecraftTransformer
import xyz.wagyourtail.unimined.internal.minecraft.transform.fixes.FixFG2ResourceLoading
import xyz.wagyourtail.unimined.internal.minecraft.transform.merge.ClassMerger
import xyz.wagyourtail.unimined.util.*
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.nio.file.*
import kotlin.io.path.*

open class FG3MinecraftTransformer(project: Project, val parent: ForgeLikeMinecraftTransformer): JarModMinecraftTransformer(
    project, parent.provider, jarModProvider = "forge", providerName = "${parent.providerName}-FG3"
) {

    val useUnionRelauncher by lazy {
        if (parent is MinecraftForgeMinecraftTransformer) {
            parent.useUnionRelaunch
        } else {
            false
        }
    }

    var unionRelauncherVersion: String = "1.1.0"

    init {
        project.logger.lifecycle("[Unimined/Forge] Using FG3 transformer")
        parent.provider.minecraftRemapper.addResourceRemapper { JsCoreModRemapper(project.logger) }
        val forgeHardcodedNames = setOf("net/minecraftforge/registries/ObjectHolderRegistry", "net/neoforged/neoforge/registries/ObjectHolderRegistry")
        parent.provider.minecraftRemapper.addExtension { StringClassNameRemapExtension(project.gradle.startParameter.logLevel) {
//            it.matches(Regex("^net/minecraftforge/.*"))
            forgeHardcodedNames.contains(it)
        } }
        unprotectRuntime = true
        parent.accessTransformerTransformer.accessTransformerPaths = listOf("fml_at.cfg", "forge_at.cfg", "META-INF/accesstransformer.cfg")
    }

    private val include: Configuration? =
        if (provider.minecraftData.mcVersionCompare(provider.version, "1.18") >= 0) {
            project.configurations.maybeCreate("include".withSourceSet(provider.sourceSet))
        } else {
            null
        }

    override val prodNamespace by lazy {
        if (userdevCfg["mcp"].asString.contains("neoform") || provider.minecraftData.mcVersionCompare(provider.version, "1.20.5") >= 0) {
            provider.mappings.getNamespace("mojmap")
        } else {
            provider.mappings.getNamespace("searge")
        }
    }

    @VisibleForTesting
    var binpatchFile: Path? = null

    override val merger: ClassMerger
        get() = throw UnsupportedOperationException("FG3+ does not support merging with unofficial merger.")

    override val transform: MutableList<(FileSystem) -> Unit> = (
            if (parent.provider.version == "1.12.2") { listOf(FixFG2ResourceLoading::fixResourceLoading) } else { emptyList() } +
            super.transform
            ).toMutableList()


    @ApiStatus.Internal
    val clientExtra = project.configurations.maybeCreate("clientExtra".withSourceSet(provider.sourceSet)).also {
        provider.minecraft.extendsFrom(it)
    }

    val mcpConfig: Dependency by lazy {
        project.dependencies.create(
            userdevCfg["mcp"]?.asString ?: "de.oceanlabs.mcp:mcp_config:${provider.version}@zip"
        )
    }

    open val obfNamespace by lazy {
        if (userdevCfg["notchObf"]?.asBoolean == true) "official"
        else if (userdevCfg["mcp"].asString.contains("neoform")) "mojmap"
        else "searge"
    }

    val mcpConfigData by lazy {
        val configuration = project.configurations.detachedConfiguration()
        configuration.dependencies.add(mcpConfig)
        configuration.resolve()
        val config = configuration.getFiles(mcpConfig, "zip").singleFile
        val configJson = config.toPath().readZipInputStreamFor("config.json") {
            JsonParser.parseReader(InputStreamReader(it)).asJsonObject
        }
        McpConfigData.fromJson(configJson)
    }

    open val userdevClassifier by lazy {
        val forgeDep = parent.forge.dependencies.first()

        // detect if userdev3 or userdev
        //   read if forgeDep has binpatches file
        val forgeUni = parent.forge.getFiles(forgeDep).singleFile
        forgeUni.toPath().readZipInputStreamFor<String?>(
            "binpatches.pack.lzma", false
        ) {
            "userdev3"
        } ?: "userdev"
    }

    val forgeUd by lazy {
        val forgeDep = parent.forge.dependencies.first()

        val userdev = "${forgeDep.group}:${forgeDep.name}:${forgeDep.version}:$userdevClassifier"

        val forgeUd = project.configurations.detachedConfiguration()
        forgeUd.dependencies.add(project.dependencies.create(userdev).apply {
            (this as ExternalDependency).isTransitive = false
        })

        // get forge userdev jar
        forgeUd.getFiles(forgeUd.dependencies.last()).singleFile
    }

    override fun beforeMappingsResolve() {
        project.logger.info("[Unimined/ForgeTransformer] FG3: beforeMappingsResolve")
        provider.mappings {
            if (obfNamespace == "mojmap") {
                mojmap()
            } else {
                val mcpConfigUserSpecified = mappingsDeps.entries.firstOrNull { it.value.dep.group == "de.oceanlabs.mcp" && it.value.dep.name == "mcp_config" }
                if (mcpConfigUserSpecified != null && !parent.customSearge) {
                    if (mcpConfigUserSpecified.value.dep.version != mcpConfig.version) {
                        project.logger.warn("[Unimined/ForgeTransformer] FG3 does not support custom mcp_config (searge) version specification. Using ${mcpConfig.version} from userdev.")
                    }
                    mappingsDeps.remove(mcpConfigUserSpecified.key)
                }
                if (!parent.customSearge) searge(mcpConfig.version!!)
            }
        }
    }

    override fun apply() {
//        val installer = "${forgeDep.group}:${forgeDep.name}:${forgeDep.version}:installer"
//        forgeInstaller.dependencies.add(project.dependencies.create(installer))

        for (element in userdevCfg.get("libraries")?.asJsonArray ?: listOf()) {
            if (element.asString.contains("legacydev")) continue
            val dep = element.asString.split(":")
            if (dep[1] == "gson" && dep[2] == "2.8.0") {
                provider.minecraftLibraries.dependencies.add(project.dependencies.create("${dep[0]}:${dep[1]}:2.8.9"))
            } else {
                provider.minecraftLibraries.dependencies.add(project.dependencies.create(element.asString))
            }
        }

        if (useUnionRelauncher) {
            provider.minecraftLibraries.dependencies.add(project.dependencies.create("io.github.juuxel:union-relauncher:$unionRelauncherVersion"))
        }

        if (userdevCfg.has("inject")) {
            project.logger.lifecycle("[Unimined/ForgeTransformer] Attempting inject forge userdev into minecraft jar")
            this.addTransform { outputJar ->
                forgeUd.toPath().forEachInZip { s, input ->
                    if (s.startsWith(userdevCfg.get("inject").asString)) {
                        val target = outputJar.getPath(s.substring(userdevCfg.get("inject").asString.length))
                        project.logger.info("[Unimined/ForgeTransformer] Injecting $s into minecraft jar")
                        Files.createDirectories(target.parent)
                        Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING)
                    }
                }
            }
        }

        userdevCfg.get("runs").asJsonObject.get("client").asJsonObject.apply {
            val mainClass = get("main").asString
            if (!mainClass.startsWith("net.minecraftforge.legacydev")) {
                project.logger.info("[Unimined/ForgeTransformer] Inserting mcp mappings")
                if (obfNamespace != "mojmap") {
                    provider.minecraftLibraries.dependencies.add(
                        project.dependencies.create(project.files(parent.srgToMCPAsMCP))
                    )
                }
            }
        }

        super.apply()
    }

    open val userdevCfg by lazy {
        forgeUd.toPath().readZipInputStreamFor("config.json") {
            JsonParser.parseReader(InputStreamReader(it)).asJsonObject
        }!!
    }

    @Throws(IOException::class)
    private fun executeMcp(step: String, outputPath: Path, envType: EnvType) {
        val type = when (envType) {
            EnvType.CLIENT -> "client"
            EnvType.SERVER -> "server"
            EnvType.COMBINED -> "joined"
            EnvType.DATAGEN -> "server"
        }
        val steps: List<McpConfigStep> = mcpConfigData.steps[type]!!
        val executor = McpExecutor(
            project,
            provider,
            outputPath.parent.resolve("mcpconfig").createDirectories(),
            steps,
            mcpConfigData.functions
        )
        val output: Path = executor.executeUpTo(step)
        Files.copy(output, outputPath, StandardCopyOption.REPLACE_EXISTING)
    }

    override fun mergedJar(clientjar: MinecraftJar, serverjar: MinecraftJar): MinecraftJar {
        return MinecraftJar(
            clientjar,
            parentPath = provider.minecraftData.mcVersionFolder
                .resolve(providerName),
            envType = EnvType.COMBINED,
            mappingNamespace = provider.mappings.getNamespace(obfNamespace),
            fallbackNamespace = provider.mappings.OFFICIAL
        )
    }

    override fun merge(clientjar: MinecraftJar, serverjar: MinecraftJar): MinecraftJar {
        project.logger.lifecycle("Merging client and server jars...")
        val output = mergedJar(clientjar, serverjar)
        createClientExtra(clientjar, serverjar, output.path)
        if (output.path.exists() && !project.unimined.forceReload) {
            return output
        }
        if (userdevCfg["notchObf"]?.asBoolean == true) {
            executeMcp("merge", output.path, EnvType.COMBINED)
        } else {
            executeMcp("rename", output.path, EnvType.COMBINED)
        }
        return output
    }

    private fun createClientExtra(
        baseMinecraftClient: MinecraftJar,
        @Suppress("UNUSED_PARAMETER") baseMinecraftServer: MinecraftJar?,
        patchedMinecraft: Path
    ) {
        val clientExtra = patchedMinecraft.parent.createDirectories()
            .resolve("client-extra-${provider.version}.jar")

        if (this.clientExtra.dependencies.isEmpty()) {
            this.clientExtra.dependencies.add(
                project.dependencies.create(
                    project.files(clientExtra.toString())
                )
            )
        }

        if (clientExtra.exists()) {
            if (project.unimined.forceReload) {
                clientExtra.deleteExisting()
            } else {
                return
            }
        }

        clientExtra.openZipFileSystem(mapOf("mutable" to true, "create" to true)).use { extra ->
            baseMinecraftClient.path.openZipFileSystem().use { base ->
                for (path in Files.walk(base.getPath("/"))) {
                    // skip meta-inf
                    if (path.nameCount > 0 && path.getName(0).toString().equals("META-INF", ignoreCase = true)) continue
//            project.logger.warn("Checking $path")
                    if (!path.isDirectory() && path.extension != "class") {
//                project.logger.warn("Copying $path")
                        val target = extra.getPath(path.toString())
                        target.parent.createDirectories()
                        path.copyTo(target, StandardCopyOption.REPLACE_EXISTING)
                    }
                }
            }
        }
    }

    override fun transform(minecraft: MinecraftJar): MinecraftJar {
        project.logger.lifecycle("[Unimined/Forge] transforming minecraft jar for FG3")
        project.logger.info("minecraft: $minecraft")

        val forgeUniversal = parent.forge.dependencies.last()

        val outFolder = minecraft.path.parent.resolve(providerName).resolve(forgeUniversal.version!!).createDirectories()

        val inputMC = if (minecraft.envType != EnvType.COMBINED) {
            // if userdev cfg says notch
            if (userdevCfg["notchObf"]?.asBoolean == true) {
                throw IllegalStateException("Forge userdev3 (legacy fg3, aka 1.12.2) is not supported for non-combined environments currently.")
            }
            // run mcp_config to rename

            val output = MinecraftJar(
                minecraft,
                parentPath = provider.minecraftData.mcVersionFolder
                    .resolve(providerName),
                mappingNamespace = provider.mappings.getNamespace(obfNamespace),
                fallbackNamespace = provider.mappings.OFFICIAL,
                patches = minecraft.patches + "mcp_config"
            )
            if (minecraft.envType == EnvType.CLIENT) {
                createClientExtra(minecraft, null, output.path)
            }
            if (!output.path.exists() || project.unimined.forceReload) {
                executeMcp("rename", output.path, minecraft.envType)
            }
            output
        } else {
            minecraft
        }

        val patchedMC = MinecraftJar(
            inputMC,
            name = if (parent is NeoForgedMinecraftTransformer && parent.provider.minecraftData.mcVersionCompare(provider.version, "1.20.1") != 0) "neoforge" else "forge",
            version = forgeUniversal.version!!,
            parentPath = outFolder
        )


        // extract binpatches
        val binPatchFile = this.binpatchFile ?: if (patchedMC.envType == EnvType.COMBINED) {
            forgeUd.toPath().readZipInputStreamFor(userdevCfg["binpatches"].asString) {
                outFolder.resolve("binpatches-joined.lzma").apply {
                    writeBytes(
                        it.readBytes(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING
                    )
                }
            }
        } else {
            // get from forge installer
            project.configurations.detachedConfiguration(project.dependencies.create(
                "${forgeUniversal.group}:${forgeUniversal.name}:${forgeUniversal.version}:installer"
            )).resolve().first { it.extension == "jar" }.toPath().readZipInputStreamFor("data/${patchedMC.envType.classifier}.lzma") {
                outFolder.resolve("binpatches-${patchedMC.envType.classifier}.lzma").apply {
                    writeBytes(
                        it.readBytes(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING
                    )
                }
            }
        }


        if (!patchedMC.path.exists() || project.unimined.forceReload) {
            patchedMC.path.deleteIfExists()
            val args = (userdevCfg["binpatcher"].asJsonObject["args"].asJsonArray.map {
                when (it.asString) {
                    "{clean}" -> inputMC.path.toString()
                    "{patch}" -> binPatchFile.toString()
                    "{output}" -> patchedMC.path.toString()
                    else -> it.asString
                }
            } + listOf("--data", "--unpatched")).toTypedArray()
            val stoutLevel = project.gradle.startParameter.logLevel
            val stdout = System.out
            if (stoutLevel > LogLevel.INFO) {
                System.setOut(PrintStream(NullOutputStream.NULL_OUTPUT_STREAM))
            }
            project.logger.info("Running binpatcher with args: ${args.joinToString(" ")}")
            try {
                ConsoleTool.main(args)
            } catch (e: Throwable) {
                e.printStackTrace()
                patchedMC.path.deleteIfExists()
                throw e
            }
            if (stoutLevel > LogLevel.INFO) {
                System.setOut(stdout)
            }
        }
        //   shade in forge jar
        val shadedForge = super.transform(patchedMC)
        return if (userdevCfg["notchObf"]?.asBoolean == true) {
            provider.minecraftRemapper.provide(shadedForge, provider.mappings.getNamespace("searge"), provider.mappings.OFFICIAL)
        } else {
            shadedForge
        }
    }

    val legacyClasspath by lazy {
        val lcp = provider.localCache.createDirectories().resolve("legacy_classpath.txt")
        lcp.writeText(
            (provider.minecraftLibraries.files + provider.minecraftFileDev + clientExtra.resolve()).joinToString(
                "\n"
            ) { it.toString() }, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING
        )
        lcp
    }

    private fun addProperties(config: RunConfig) {
        config.properties.putAll(mapOf(
            "minecraft_classpath_file" to {
                legacyClasspath.absolutePathString()
            },
            "modules" to {
                val libs = mapOf(*provider.minecraftLibraries.dependencies.map { it.group + ":" + it.name + ":" + it.version to it }
                    .toTypedArray())
                userdevCfg.get("modules").asJsonArray.joinToString(File.pathSeparator) {
                    val dep = libs[it.asString.removeSuffix("@jar")]
                        ?: throw IllegalStateException("Module ${it.asString} not found in mc libraries")
                    provider.minecraftLibraries.getFiles(dep).singleFile.toString()
                }
            }
        ))
    }

    private fun getArgValue(arg: String): String {
        return if (arg.startsWith("{")) {
            when (arg) {
                "{asset_index}" -> provider.minecraftData.metadata.assetIndex?.id ?: ""
                "{mcp_mappings}" -> "unimined.stub"
                "{natives}" -> "\${natives_directory}"
                else -> "\$$arg"
            }
        } else {
            arg
        }
    }

    override fun applyClientRunTransform(config: RunConfig) {
        super.applyClientRunTransform(config)
        config.properties["minecraft_classpath_file"] = {
            legacyClasspath.absolutePathString()
        }
        config.properties["source_roots"] = {
            parent.groups
        }
        userdevCfg.get("runs").asJsonObject.get("client").asJsonObject.apply {
            val mainClass = if (useUnionRelauncher) {
                config.jvmArgs("-DunionRelauncher.mainClass=${get("main").asString}")
                "juuxel.unionrelauncher.UnionRelauncher"
            } else {
                get("main").asString
            }

            parent.tweakClassClient = get("env")?.asJsonObject?.get("tweakClass")?.asString
            if (mainClass.startsWith("net.minecraftforge.legacydev")) {
                project.logger.info("[FG3] Using legacydev launchwrapper")
                config.mainClass.set("net.minecraft.launchwrapper.Launch")
                config.jvmArgs(
                    "-Dfml.deobfuscatedEnvironment=true",
                    "-Dfml.ignoreInvalidMinecraftCertificates=true",
                    "-Dnet.minecraftforge.gradle.GradleStart.srg.srg-mcp=${parent.srgToMCPAsSRG}"
                )
                config.args(
                    "--tweakClass",
                    parent.tweakClassClient ?: "net.minecraftforge.fml.common.launcher.FMLTweaker"
                )
                config.environment["MOD_CLASSES"] = parent.groups
            } else {
                project.logger.info("[FG3] Using new client run config")
                val args = get("args")?.asJsonArray?.map { it.asString } ?: listOf()
                val jvmArgs = get("jvmArgs")?.asJsonArray?.map { it.asString } ?: listOf()
                val env = get("env")?.asJsonObject?.entrySet()?.associate { it.key to it.value.asString } ?: mapOf()
                val props = get("props")?.asJsonObject?.entrySet()?.associate { it.key to it.value.asString } ?: mapOf()
                addProperties(config)
                config.mainClass.set(mainClass)
                config.args = args.map { getArgValue(it) }
                config.jvmArgs(jvmArgs.map { getArgValue(it) })
                config.jvmArgs(props.map { "-D${it.key}=${getArgValue(it.value)}" })
                config.environment["FORGE_SPEC"] = userdevCfg.get("spec").asNumber.toString()
                config.environment.putAll(env.map { it.key to getArgValue(it.value) })
                config.environment.computeIfAbsent("MOD_CLASSES") {
                    getArgValue("{source_roots}")
                }
            }
        }

    }

    override fun applyServerRunTransform(config: RunConfig) {
        super.applyServerRunTransform(config)
        config.properties["minecraft_classpath_file"] = {
            legacyClasspath.absolutePathString()
        }
        config.properties["source_roots"] = {
            parent.groups
        }
        userdevCfg.get("runs").asJsonObject.get("server").asJsonObject.apply {
            val mainClass = get("main").asString
            parent.tweakClassServer = get("env")?.asJsonObject?.get("tweakClass")?.asString
            if (mainClass.startsWith("net.minecraftforge.legacydev")) {
                project.logger.info("[FG3] Using legacydev launchwrapper")
                config.mainClass.set("net.minecraft.launchwrapper.Launch")
                config.jvmArgs(
                    "-Dfml.ignoreInvalidMinecraftCertificates=true",
                    "-Dfml.deobfuscatedEnvironment=true",
                    "-Dnet.minecraftforge.gradle.GradleStart.srg.srg-mcp=${parent.srgToMCPAsSRG}"
                )
                config.args (
                    "--tweakClass",
                    parent.tweakClassClient ?: "net.minecraftforge.fml.common.launcher.FMLTweaker"
                )
                config.environment["MOD_CLASSES"] = parent.groups
            } else {
                project.logger.info("[FG3] Using new server run config")
                val args = get("args")?.asJsonArray?.map { it.asString } ?: listOf()
                val jvmArgs = get("jvmArgs")?.asJsonArray?.map { it.asString } ?: listOf()
                val env = get("env")?.asJsonObject?.entrySet()?.associate { it.key to it.value.asString } ?: mapOf()
                val props = get("props")?.asJsonObject?.entrySet()?.associate { it.key to it.value.asString } ?: mapOf()
                addProperties(config)
                config.mainClass.set(mainClass)
                config.args = args.map { getArgValue(it) }
                config.jvmArgs(jvmArgs.map { getArgValue(it) })
                config.jvmArgs(props.map { "-D${it.key}=${getArgValue(it.value)}" })
                config.environment["FORGE_SPEC"] = userdevCfg.get("spec").asNumber.toString()
                config.environment.putAll(env.map { it.key to getArgValue(it.value) })
                config.environment.computeIfAbsent("MOD_CLASSES") {
                    getArgValue("{source_roots}")
                }
            }
        }
    }

    override fun applyExtraLaunches() {
        super.applyExtraLaunches()
        if (provider.side == EnvType.DATAGEN) {
            TODO("DATAGEN not supported yet")
        }
    }

    override fun afterRemap(baseMinecraft: MinecraftJar): MinecraftJar {
        return parent.accessTransformerTransformer.afterRemap(fixForge(baseMinecraft))
    }

    private fun addIncludeToMetadata(json: JsonObject, dep: ResolvedArtifact, path: String) {
        var jars = json.get("jars")?.asJsonArray
        if (jars == null) {
            jars = JsonArray()
            json.add("jars", jars)
        }
        jars.add(JsonObject().apply {
            add("identifier", JsonObject().apply {
                addProperty("group", dep.moduleVersion.id.group)
                addProperty("artifact", dep.name)
            })
            add("version", JsonObject().apply {
                addProperty("range", "[${dep.moduleVersion.id.version},)")
                addProperty("artifactVersion", dep.moduleVersion.id.version)
            })
            addProperty("path", path)
        })
    }

    private fun doJarJar(remapJarTask: AbstractRemapJarTask, output: Path) {
        val deps = include!!.resolvedConfiguration.resolvedArtifacts
        if (deps.isEmpty()) {
            return
        }
        output.openZipFileSystem(mapOf("mutable" to true)).use { fs ->
            val json = JsonObject()
            val jarDir = fs.getPath("META-INF/jarjar/")
            val mod = jarDir.resolve("metadata.json")

            jarDir.createDirectories()
            var errored = false
            for (dep in deps) {
                try {
                    val path = jarDir.resolve("${dep.name}-${dep.moduleVersion.id.version}${dep.classifier?.let { "-$it" } ?: ""}.jar")
                    if (!path.exists()) {
                        dep.file.toPath()
                            .copyTo(jarDir.resolve("${dep.name}-${dep.moduleVersion.id.version}${dep.classifier?.let { "-$it" } ?: ""}.jar"), true)
                    }

                    addIncludeToMetadata(json, dep, "META-INF/jarjar/${dep.name}-${dep.moduleVersion.id.version}${dep.classifier?.let { "-$it" } ?: ""}.jar")
                } catch (e: Exception) {
                    project.logger.error("Failed on $dep", e)
                    errored = true
                }
            }
            if (errored) {
                throw IllegalStateException("An error occured resolving includes")
            }

            mod.writeBytes(FabricLikeMinecraftTransformer.GSON.toJson(json).toByteArray())
        }
    }

    override fun afterRemapJarTask(remapJarTask: AbstractRemapJarTask, output: Path) {
        if (provider.minecraftData.mcVersionCompare(provider.version, "1.18") >= 0) {
            doJarJar(remapJarTask, output)
        }
    }

    private fun fixForge(baseMinecraft: MinecraftJar): MinecraftJar {
        if (!baseMinecraft.patches.contains("fixForge") && baseMinecraft.mappingNamespace != provider.mappings.OFFICIAL) {
            val target = MinecraftJar(
                baseMinecraft,
                patches = baseMinecraft.patches + "fixForge",
            )

            if (target.path.exists() && !project.unimined.forceReload) {
                return target
            }

            Files.copy(baseMinecraft.path, target.path, StandardCopyOption.REPLACE_EXISTING)
            try {
                target.path.openZipFileSystem(mapOf("mutable" to true)).use { out ->
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

    override fun createSourcesJar(
        classpath: FileCollection,
        patchedJar: Path,
        outputPath: Path,
        linemappedPath: Path?
    ) {
        //TODO: replace with mcp_config patches
        super.createSourcesJar(classpath, patchedJar, outputPath, linemappedPath)
    }
}
