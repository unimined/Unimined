package xyz.wagyourtail.unimined.internal.mapping

import com.google.gson.JsonParser
import net.fabricmc.mappingio.MappingVisitor
import net.fabricmc.mappingio.adapter.MappingSourceNsSwitch
import net.fabricmc.mappingio.format.MappingTreeBuilder
import net.fabricmc.mappingio.format.ProGuardReader
import net.fabricmc.mappingio.format.Tiny2Reader2
import net.fabricmc.mappingio.format.Tiny2Writer2
import net.fabricmc.mappingio.tree.MappingTreeView
import net.fabricmc.mappingio.tree.MemoryMappingTree
import net.fabricmc.tinyremapper.IMappingProvider
import org.gradle.api.Project
import org.gradle.api.artifacts.FileCollectionDependency
import xyz.wagyourtail.unimined.api.mapping.MappingDepConfig
import xyz.wagyourtail.unimined.api.mapping.MappingsConfig
import xyz.wagyourtail.unimined.api.mapping.MemoryMapping
import xyz.wagyourtail.unimined.api.minecraft.EnvType
import xyz.wagyourtail.unimined.api.minecraft.MinecraftConfig
import xyz.wagyourtail.unimined.api.unimined
import xyz.wagyourtail.unimined.util.*
import java.io.IOException
import java.io.StringWriter
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import kotlin.io.path.*

class MappingsProvider(project: Project, minecraft: MinecraftConfig, val mappingKey: String = "mappings"): MappingsConfig(project, minecraft) {

    override var side: EnvType by FinalizeOnRead(LazyMutable { minecraft.side })

    private var freeze by FinalizeOnWrite(false)

    override var devNamespace: Namespace by FinalizeOnRead(LazyMutable {
        getNamespaces().values.firstOrNull { it.named } ?: throw IllegalStateException("No named namespace found in ${getNamespaces().keys}")
    })

    override var devFallbackNamespace: Namespace by FinalizeOnRead(LazyMutable {
        devNamespace.targets.firstOrNull { it != OFFICIAL } ?: if (devNamespace.targets.contains(OFFICIAL)) OFFICIAL else throw IllegalStateException("No fallback namespace found")
    })

    override val mappingsDeps = mutableMapOf<String, MappingDepConfig>()

    override fun removeKey(key: String) {
        if (freeze) throw IllegalStateException("Cannot remove key after freeze")
        mappingsDeps.remove(key)
    }


    override fun devNamespace(namespace: String) {
        val delegate: FinalizeOnRead<Namespace> = MappingsProvider::class.getField("devNamespace")!!.getDelegate(this) as FinalizeOnRead<Namespace>
        delegate.setValueIntl(LazyMutable { getNamespace(namespace) })
    }

    override fun devFallbackNamespace(namespace: String) {
        val delegate: FinalizeOnRead<Namespace> = MappingsProvider::class.getField("devFallbackNamespace")!!.getDelegate(this) as FinalizeOnRead<Namespace>
        delegate.setValueIntl(LazyMutable { getNamespace(namespace) })
    }

    override fun intermediary(key: String, action: MappingDepConfig.() -> Unit) {
        project.unimined.fabricMaven()
        mapping("net.fabricmc:intermediary:${minecraft.version}:v2", key) {
            outputs("intermediary", false) { listOf("official") }
            action()
        }
    }

    override fun calamus(key: String, action: MappingDepConfig.() -> Unit) {
        project.unimined.ornitheMaven()
        val environment = if (minecraft.minecraftData.mcVersionCompare(minecraft.version, "1.3") < 0) {
            when (side) {
                EnvType.CLIENT -> "-client"
                EnvType.SERVER -> "-server"
                else -> throw IllegalStateException("Cannot use Calamus on Minecraft ${minecraft.version} with side COMBINED")
            }
        } else {
            ""
        }
        mapping("net.ornithemc:calamus-intermediary:${minecraft.version}${environment}:v2", key) {
            outputs("intermediary", false) { listOf("official") }
            action()
        }
    }

    override fun legacyIntermediary(revision: Int, key: String, action: MappingDepConfig.() -> Unit) {
        project.unimined.legacyFabricMaven()
        if (legacyFabricMappingsVersionFinalize.value != revision) {
            if (!legacyFabricMappingsVersionFinalize.finalized) {
                legacyFabricMappingsVersion = revision
                legacyFabricMappingsVersionFinalize.finalized = true
            } else {
                project.logger.warn("[Unimined/MappingsProvider] Different revisions of legacy fabric mappings were used. This will most likely cause issues.")
            }
        }
        val group = if (revision < 2) {
            "net.legacyfabric"
        } else {
            "net.legacyfabric.v${revision}"
        }
        mapping("${group}:intermediary:${minecraft.version}:v2", key) {
            outputs("intermediary", false) { listOf("official") }
            action()
        }
    }

    override fun babricIntermediary(key: String, action: MappingDepConfig.() -> Unit) {
        project.unimined.glassLauncherMaven("babric")
        if (side != EnvType.COMBINED) {
            mapping("babric:intermediary:${minecraft.version}:v2", key) {
                mapNamespace(side.classifier!!, "official")
                outputs("intermediary", false) { listOf("official") }
            }
        } else {
            postProcess("$key-client", {
                side = EnvType.CLIENT
                babricIntermediary(key, action)
            }) {
                sourceNamespace("intermediary")
                mapNamespace("official", EnvType.CLIENT.classifier!!)
                outputs("intermediary", false) { emptyList() }
                outputs(EnvType.CLIENT.classifier!!, false) { listOf("intermediary") }
            }
            postProcess("$key-server", {
                side = EnvType.SERVER
                babricIntermediary(key, action)
            }) {
                sourceNamespace("intermediary")
                mapNamespace("official", EnvType.SERVER.classifier!!)
                outputs("intermediary", false) { emptyList() }
                outputs(EnvType.SERVER.classifier!!, false) { listOf("intermediary") }
            }
        }
    }

    override fun officialMappingsFromJar(key: String, action: MappingDepConfig.() -> Unit) {
        val mcFile = when (minecraft.side) {
            EnvType.CLIENT -> minecraft.minecraftData.minecraftClientFile
            EnvType.COMBINED -> minecraft.mergedOfficialMinecraftFile
            EnvType.SERVER, EnvType.DATAGEN -> minecraft.minecraftData.minecraftServerFile
        }
        mapping(project.files(mcFile), key) {
            outputs("official", false) { listOf() }
            action()
        }
    }

    override fun searge(version: String, key: String, action: MappingDepConfig.() -> Unit) {
        project.unimined.minecraftForgeMaven()
        val mappings = if (minecraft.minecraftData.mcVersionCompare(minecraft.version, "1.12.2") < 0) {
            "de.oceanlabs.mcp:mcp:${version}:srg@zip"
        } else {
            "de.oceanlabs.mcp:mcp_config:${version}@zip"
        }
        officialMappingsFromJar {
            action()
        }
        // if we need to replace class names with mojmap
        if (minecraft.minecraftData.mcVersionCompare(minecraft.version, "1.16.5") > 0) {
            postProcess(key, {
                mojmap()
                mapping(mappings, key) {
                    mapNamespace("obf", "official")
                    dependsOn("mojmap")
                    memberNameReplacer("srg", "mojmap", setOf("class"))
                    onlyExistingSrc()
                    dependsOn("mojmap")
                    outputs("srg", false) { listOf("official") }
                }
            }) {
                mapNamespace("srg", "searge")
                outputs("searge", false) { listOf("official") }
                action()
            }
        } else {
            mapping(mappings, key) {
                mapNamespace("obf", "official")
                outputs("searge", false) { listOf("official") }
                onlyExistingSrc()
                action()
            }
        }
    }

    override fun hashed(key: String, action: MappingDepConfig.() -> Unit) {
        project.unimined.quiltMaven()
        mapping("org.quiltmc:hashed:${minecraft.version}", key) {
            outputs("hashed", false) { listOf("official") }
            action()
        }
    }

    override fun mojmap(key: String, action: MappingDepConfig.() -> Unit) {
        val mapping = when (minecraft.side) {
            EnvType.CLIENT, EnvType.COMBINED -> "client"
            EnvType.SERVER, EnvType.DATAGEN -> "server"
        }
        mapping("net.minecraft:$mapping-mappings:${minecraft.version}", key) {
            outputs("mojmap", true) {
                // check if we have searge or intermediary or hashed mappings
                val searge = if ("searge" in getNamespaces()) listOf("searge") else emptyList()
                val intermediary = if ("intermediary" in getNamespaces()) listOf("intermediary") else emptyList()
                val hashed = if ("hashed" in getNamespaces()) listOf("hashed") else emptyList()
                listOf("official") + intermediary + searge + hashed
            }
            action()
        }
    }

    override fun mcp(channel: String, version: String, key: String, action: MappingDepConfig.() -> Unit) {
        if (channel == "legacy") {
            project.unimined.wagYourMaven("releases")
            officialMappingsFromJar {
                action()
            }
        } else {
            project.unimined.minecraftForgeMaven()
        }
        mapping("de.oceanlabs.mcp:mcp_${channel}:${version}@zip", key) {
            if (channel == "legacy") {
                contains({ _, t ->
                    t != "MCP"
                }) {
                    onlyExistingSrc()
                    outputs("searge", false) { listOf("official") }
                }
                contains({ _, t ->
                    t == "MCP" || t == "OLDER_MCP"
                }) {
                    onlyExistingSrc()
                    outputs("mcp", true) { listOf("searge") }
                    sourceNamespace("searge")
                }
                contains({_, t ->
                    t == "OLD_MCP"
                }) {
                    onlyExistingSrc()
                    outputs("searge", false) { listOf("official") }
                    outputs("mcp", true) { listOf("searge") }
                }
                contains({f, t ->
                    t == "OLD_MCP" && !f.contains("classes")
                }) {
                    // make classes first
                    dependsOn("searge")
                }
            } else {
                onlyExistingSrc()
                outputs("mcp", true) { listOf("searge") }
                sourceNamespace("searge")
            }
            action()
        }
    }

    override fun retroMCP(version: String, key: String, action: MappingDepConfig.() -> Unit) {
        project.unimined.mcphackersIvy()
        mapping("io.github.mcphackers:mcp:${version}@zip", key) {
            if (minecraft.minecraftData.mcVersionCompare(version, "1.3") < 0) {
                if (side == EnvType.COMBINED) throw IllegalStateException("Cannot use retroMCP with side COMBINED")
                mapNamespace(side.classifier!!, "official")
            }
            mapNamespace("named", "mcp")
            outputs("mcp", true) { listOf("official") }
            action()
        }
    }

    override fun yarn(build: Int, key: String, action: MappingDepConfig.() -> Unit) {
        project.unimined.fabricMaven()
        mapping("net.fabricmc:yarn:${minecraft.version}+build.${build}:v2", key) {
            outputs("yarn", true) { listOf("intermediary") }
            mapNamespace("named", "yarn")
            sourceNamespace("intermediary")
            renest()
            action()
        }
    }

    override fun yarnv1(build: Int, key: String, action: MappingDepConfig.() -> Unit) {
        project.unimined.fabricMaven()
        val files = project.configurations.detachedConfiguration(project.dependencies.create("net.fabricmc:yarn:${minecraft.version}+build.${build}")).resolve()
        val jar = files.first { it.extension == "jar" }
        val temp = Files.createTempFile("yarnv1+${build}", ".tiny")
        jar.toPath().readZipInputStreamFor("mappings/mappings.tiny") {
            it.copyTo(temp.outputStream(StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING))
        }
        val output = Files.createTempFile("yarnv1+${build}+filled", ".tiny")

        project.execOps.javaexec {
            it.classpath = project.configurations.detachedConfiguration(
                project.dependencies.create(
                    "net.fabricmc:stitch:0.6.2"
                )
            )
            it.mainClass.set("net.fabricmc.stitch.Main")
            it.args = listOf(
                "proposeFieldNames",
                minecraft.minecraftData.minecraftClientFile.absolutePath,
                temp.absolutePathString(),
                output.absolutePathString()
            )
        }.assertNormalExitValue().rethrowFailure()

        mapping(project.files(output.toFile()), key) {
            outputs("yarn", true) { listOf("intermediary") }
            mapNamespace("named", "yarn")
            sourceNamespace("intermediary")
            renest()
            action()
        }
    }

    override fun feather(build: Int, key: String, action: MappingDepConfig.() -> Unit) {
        project.unimined.ornitheMaven()
        val environment = when (side) {
            EnvType.CLIENT -> "-client"
            EnvType.SERVER -> "-server"
            else -> ""
        }
        mapping("net.ornithemc:feather:${minecraft.version}${environment}+build.${build}:v2", key) {
            outputs("yarn", true) { listOf("intermediary") }
            mapNamespace("named", "yarn")
            sourceNamespace("intermediary")
            renest()
            action()
        }
    }

    override fun legacyYarn(build: Int, revision: Int, key: String, action: MappingDepConfig.() -> Unit) {
        project.unimined.legacyFabricMaven()
        if (legacyFabricMappingsVersionFinalize.value != revision) {
            if (!legacyFabricMappingsVersionFinalize.finalized) {
                legacyFabricMappingsVersion = revision
                legacyFabricMappingsVersionFinalize.finalized = true
            } else {
                project.logger.warn("[Unimined/MappingsProvider] Different revisions of legacy fabric mappings were used. This will most likely cause issues.")
            }
        }
        val group = if (revision < 2) {
            "net.legacyfabric"
        } else {
            "net.legacyfabric.v${revision}"
        }
        mapping("${group}:yarn:${minecraft.version}+build.${build}:v2", "yarn") {
            outputs("yarn", true) { listOf("intermediary") }
            mapNamespace("named", "yarn")
            sourceNamespace("intermediary")
            action()
        }
    }

    override fun barn(build: Int, key: String, action: MappingDepConfig.() -> Unit) {
        project.unimined.glassLauncherMaven("babric")
        mapping("babric:barn:${minecraft.version}+build.${build}:v2", "yarn") {
            outputs("barn", true) { listOf("intermediary") }
            mapNamespace("named", "barn")
            sourceNamespace("intermediary")
            action()
        }
    }

    override fun biny(commitName: String, key: String, action: MappingDepConfig.() -> Unit) {
        project.unimined.glassLauncherMaven("releases")
        mapping("net.glasslauncher:biny:${minecraft.version}+${commitName}:v2", "yarn") {
            outputs("biny", true) { listOf("intermediary") }
            mapNamespace("named", "biny")
            sourceNamespace("intermediary")
            action()
        }
    }

    override fun quilt(build: Int, classifier: String, key: String, action: MappingDepConfig.() -> Unit) {
        project.unimined.quiltMaven()
        mapping("org.quiltmc:quilt-mappings:${minecraft.version}+build.${build}:${classifier}", "quilt") {
            mapNamespace("named", "quilt")
            val intermediary = if (classifier.contains("intermediary")) listOf("intermediary") else emptyList()
            val hashed = if (intermediary.isEmpty()) listOf("hashed") else emptyList()
            outputs("quilt", true) {
                intermediary + hashed
            }
            sourceNamespace((intermediary + hashed).first())
            action()
        }
    }

    override fun freeze() {
        for ((key, dep) in mappingsDeps) {
            project.logger.info("[Unimined/MappingsProvider] Finalizing $key (${dep.dep})")
            (dep as MappingDepConfigImpl).finalize()
        }
        super.freeze()
    }

    override fun forgeBuiltinMCP(version: String, key: String, action: MappingDepConfig.() -> Unit) {
        project.unimined.minecraftForgeMaven()
        officialMappingsFromJar {
            action()
        }
        mapping("net.minecraftforge:forge:${minecraft.version}-${version}:src@zip", "mcp") {
            contains({ _, t ->
                !t.contains("MCP")
            }) {
                onlyExistingSrc()
                outputs("searge", false) { listOf("official") }
            }
            contains({ _, t ->
                t.contains("MCP")
            }) {
                outputs("mcp", true) { listOf("searge") }
                sourceNamespace("searge")
            }
            action()
        }
    }

    override fun parchment(
        mcVersion: String,
        version: String,
        checked: Boolean,
        key: String,
        action: MappingDepConfig.() -> Unit
    ) {
        project.unimined.parchmentMaven()
        val artifact = "org.parchmentmc.data:parchment-${mcVersion}:${version}" + (if (checked) ":checked" else "") + "@zip"
        mapping(artifact, key) {
            this as MappingDepConfigImpl
            sourceNamespace("mojmap")
            outputs("mojmap", true) { listOf() }
            allowDuplicateOutputs()
            action()
        }
    }

    override fun spigotDev(mcVersion: String, key: String, action: MappingDepConfig.() -> Unit) {

        // TODO: have a common sourceSet where it would make sense to put this
        val cache = project.unimined.getLocalCache(minecraft.sourceSet).resolve("spigot").resolve(mcVersion)
        cache.createDirectories()
        val buildInfo = run {
            val buildInfoFile = cache.resolve("BuildInfo-${mcVersion}.json")
            project.cachingDownload(URI.create("https://hub.spigotmc.org/versions/${mcVersion}.json"), cachePath = cache)
            val buildInfoJson = JsonParser.parseString(buildInfoFile.readText())
            buildInfoJson.asJsonObject
        }

        val buildDataZip = run {
            val buildDataFile = cache.resolve("BuildData-${mcVersion}-${buildInfo["name"].asString}.zip")
            if (!buildDataFile.exists() || project.unimined.forceReload) {
                val version = buildInfo["refs"].asJsonObject["BuildData"].asString
                project.cachingDownload(
                    URI.create("https://hub.spigotmc.org/stash/rest/api/latest/projects/SPIGOT/repos/builddata/archive?at=$version&format=zip"),
                    cachePath = buildDataFile
                )
            }
            buildDataFile
        }

        val (layerMojmap, hasMembers) = buildDataZip.readZipInputStreamFor("info.json") { ifs ->
            val info = ifs.reader().use { JsonParser.parseReader(it).asJsonObject }

            listOf(info.has("mappingsUrl"), info.has("memberMappings"))
        }

        if (layerMojmap) {
            project.logger.lifecycle("[Unimined/MappingsProvider] Layering mojmap on top of spigot")
            postProcess(key, {
                mojmap()
                mapping(project.files(buildDataZip), key) {
                    mapNamespace("spigot", "spigot_dev")
                    outputs("spigot_dev", true) { listOf("official", "mojmap") }
                    dependsOn("mojmap")
                    memberNameReplacer("spigot_dev", "mojmap", if (hasMembers) setOf("field") else setOf("method", "field", "method_arg", "method_var"))
                    renest()
                }
            }) {
                outputs("spigot_dev", true) { listOf("official") }
                action()
            }
        } else {
            mapping(project.files(buildDataZip), key) {
                mapNamespace("spigot", "spigot_dev")
                outputs("spigot_dev", true) { listOf("official") }
                renest()
                action()
            }
        }

    }

    override fun postProcess(key: String, mappings: MappingsConfig.() -> Unit, merger: MappingDepConfig.() -> Unit) {
        val mappingsConfig = MappingsProvider(project, minecraft, "postProcess-$key")
        mappingsConfig.mappings()
        mappingsConfig.resolveMappingTree()
        mapping(project.dependencies.create(
            project.files(mappingsConfig.mappingCacheFile()),
        ), key, merger)
    }

    override fun mapping(dependency: Any, key: String, action: MappingDepConfig.() -> Unit) {
        if (freeze) {
            throw IllegalStateException("Cannot add mappings after mapping tree has been initialized")
        }
        if (project.logger.isDebugEnabled) {
            project.logger.debug("[Unimined/MappingsProvider] Adding $key -> $dependency")
            Thread.dumpStack()
        }

        mappingsDeps.computeIfAbsent(key) {
            project.dependencies.create(dependency).let { dep ->
                project.logger.info("[Unimined/MappingsProvider] Adding mapping dependency $dependency")
                MappingDepConfigImpl(dep, this)
            }
        }.apply(action)
    }

    private var _stub: MemoryMapping? = null

    override val stub: MemoryMapping
        get() {
            if (freeze) {
                throw IllegalStateException("Cannot access stub after mapping tree has been initialized")
            }
            if (_stub == null) {
                _stub = MemoryMapping()
            }
            return _stub!!
        }

    override val hasStubs: Boolean
        get() =_stub != null

    fun getMojmapMappings(): MemoryMappingTree {
        val tree = MemoryMappingTree()
        when (minecraft.side) {
            EnvType.COMBINED, EnvType.CLIENT -> minecraft.minecraftData.officialClientMappingsFile
            EnvType.SERVER, EnvType.DATAGEN -> minecraft.minecraftData.officialServerMappingsFile
        }.inputStream().use {
            ProGuardReader.read(
                it.reader(), "mojmap", "official",
                MappingSourceNsSwitch(tree, "official")
            )
        }
        return tree
    }

    fun resolveMappingTree(): MappingTreeView {
        project.logger.lifecycle("[Unimined/MappingsProvider] Resolving mappings for ${minecraft.sourceSet}")
        lateinit var mappings: MappingTreeView
        if (!freeze) freeze = true

        if (mappingsDeps.isEmpty()) {
            project.logger.warn("[Unimined/MappingsProvider] No mappings specified!")
            return MemoryMappingTree()
        }

        project.logger.info("[Unimined/MappingsProvider] Loading mappings: \n    ${mappingsDeps.entries.joinToString("\n    ") { (it.key to it.value.dep.toString()).toString() }}")

        // if has cache and not force reload
        val cacheFile = mappingCacheFile()
        cacheFile.parent.createDirectories()
        val loaded = if (cacheFile.exists() && !project.unimined.forceReload) {
            project.logger.info("[Unimined/MappingsProvider] Loading mappings from cache")
            // load from cache
            try {
                cacheFile.reader().use {
                    MemoryMappingTree().also { map ->
                        Tiny2Reader2.read(it, map)
                        mappings = map
                    }
                }
                true
            } catch (e: IOException) {
                project.logger.warn("[Unimined/MappingsProvider] Failed to load mappings from cache, reloading from deps ${e.message}")
                // delete cache
                cacheFile.deleteExisting()
                false
            }
        } else false
        if (!loaded) {
            val configuration = project.configurations.detachedConfiguration().also {
                it.dependencies.addAll(mappingsDeps.values.map { it.dep })
            }
            val mappingBuilder = MappingTreeBuilder()
            mappingBuilder.side(minecraft.side)

            // parse each dep
            for (dep in mappingsDeps.values) {
                dep as MappingDepConfigImpl
                project.logger.lifecycle("[Unimined/MappingsProvider] Loading mappings from ${dep.dep}")
                // resolve dep to files, no pom
                val files = configuration.getFiles(dep.dep) { it.extension != "pom" }

                // load each file
                project.logger.lifecycle("[Unimined/MappingsProvider] Loading mappings files ${files.joinToString(", ")}")

                for (file in files) {
                    if (minecraft.isMinecraftJar(file.toPath())) {
                        mappingBuilder.bytecodeJar(file.toPath(), dep.inputs)
                    } else {
                        mappingBuilder.mappingFile(file.toPath(), dep.inputs)
                    }
                }
            }
            mappings = mappingBuilder.build()
            if (hasStubs) {
                project.logger.info("[Unimined/MappingsProvider] Loading stub mappings")
                _stub!!.visit(mappings as MappingVisitor)
            }
            cacheFile.bufferedWriter().use {
                mappings.accept(Tiny2Writer2(it, false))
            }
        }

        project.logger.lifecycle("[Unimined/MappingsProvider] Mapping tree initialized, ${mappings.srcNamespace} -> ${mappings.dstNamespaces.filter { it != "srg" }}")
        return mappings
    }

    val mappingTree: MappingTreeView by lazy {
        resolveMappingTree()
    }

    public fun mappingCacheFile(): Path =
        (if (hasStubs) minecraft.localCache else project.unimined.getGlobalCache())
            .resolve("mappings").resolve("${exportKey()}.tiny")

    public fun exportKey(): String =
        "${mappingKey}-${side}-${combinedNames}"

    override val combinedNames: String by lazy {
        if (!freeze) freeze = true
        val names = mappingsDeps.map { if (it.value.dep is FileCollectionDependency) ("${it.key}-${(it.value.dep as FileCollectionDependency).files.first().getShortSha1()}") else "${it.key}-${it.value.dep.version}" }.sorted() + (if (hasStubs) listOf("stub-${_stub!!.hash}") else listOf())
        names.joinToString("-")
    }

    fun MappingTreeView.sha256() {
        val sha = MessageDigest.getInstance("SHA-256")
        val stringWriter = StringWriter()
        this.accept(Tiny2Writer2(stringWriter, false))
        sha.update(stringWriter.toString().toByteArray())
        sha.digest().toHex().substring(0..8)
    }

    private fun memberOf(className: String, memberName: String, descriptor: String?): IMappingProvider.Member {
        return IMappingProvider.Member(className, memberName, descriptor)
    }

//    private fun fixInnerClassName(
//        mappings: MappingTreeView,
//        fromId: Int,
//        toId: Int,
//        fromClassName: String,
//        toClassName: String?
//    ): String? {
//        var fixedClassName = toClassName
//        val outerClass = fromClassName.substring(0, fromClassName.lastIndexOf('$'))
//        val outerClassDef = mappings.getClass(outerClass, fromId)
//        if (outerClassDef != null) {
//            val outerFromClassName = outerClassDef.getName(fromId)
//            var outerToClassName = outerClassDef.getName(toId)
//            if (outerFromClassName != null && outerFromClassName.contains('$')) {
//                outerToClassName = fixInnerClassName(
//                    mappings,
//                    fromId,
//                    toId,
//                    outerFromClassName,
//                    outerToClassName
//                )
//            }
//            val innerClassName = fixedClassName?.substringAfterLast('/')?.substringAfterLast('$') ?: fromClassName.substringAfterLast('$')
//            if (outerToClassName != null && (fixedClassName == null || !fixedClassName.startsWith(outerToClassName))) {
//                fixedClassName = "$outerToClassName$$innerClassName"
//                project.logger.info(
//                    "[Unimined/MappingsProvider] Detected missing inner class, replacing with: {} -> {}",
//                    fromClassName,
//                    fixedClassName
//                )
//            }
//        }
//        return fixedClassName
//    }

    override fun getTRMappings(
        remap: Pair<Namespace, Namespace>,
        remapLocals: Boolean,
    ) : (IMappingProvider.MappingAcceptor) -> Unit {
        this.freeze()
        return { acceptor ->
            val srcName = remap.first
            val dstName = remap.second

            val fromId = mappingTree.getNamespaceId(srcName.name)
            val toId = mappingTree.getNamespaceId(dstName.name)

            if (fromId == MappingTreeView.NULL_NAMESPACE_ID) {
                throw IllegalArgumentException("Unknown source namespace: $srcName")
            }

            if (toId == MappingTreeView.NULL_NAMESPACE_ID) {
                throw IllegalArgumentException("Unknown target namespace: $dstName")
            }

            for (classDef in mappingTree.classes) {
                var fromClassName = classDef.getName(fromId)
                var toClassName = classDef.getName(toId)

                if (fromClassName == null) {
                    if (project.logger.isDebugEnabled) project.logger.debug("[Unimined/MappingsProvider] Target class {} has no name in src namespace {}", classDef, srcName)
                    fromClassName = toClassName
                }

                // detect missing inner class
//            if (fromClassName != null && fromClassName.contains("$")) {
//                toClassName = fixInnerClassName(
//                    mappingTree,
//                    fromId,
//                    toId,
//                    fromClassName,
//                    toClassName
//                )
//            }

                if (toClassName == null) {
                    if (project.logger.isDebugEnabled) project.logger.debug("[Unimined/MappingsProvider] Target class {} has no name in dst namespace {}", classDef, dstName)
                    toClassName = fromClassName
                }

                if (fromClassName == null) {
                    if (project.logger.isDebugEnabled) project.logger.debug("[Unimined/MappingsProvider] Class $classDef has no name in either namespace $srcName or $dstName")
                    continue
                }

                acceptor.acceptClass(fromClassName, toClassName)

                for (fieldDef in classDef.fields) {
                    val fromFieldName = fieldDef.getName(fromId)
                    val toFieldName = fieldDef.getName(toId)

                    if (fromFieldName == null) {
                        if (project.logger.isDebugEnabled) project.logger.debug("[Unimined/MappingsProvider] Target field {} has no name in src namespace {}", fieldDef, srcName)
                        continue
                    }

                    if (toFieldName == null) {
                        if (project.logger.isDebugEnabled) project.logger.debug("[Unimined/MappingsProvider] Target field {} has no name in dst namespace {}", fieldDef, dstName)
                        continue
                    }

                    acceptor.acceptField(memberOf(fromClassName, fromFieldName, fieldDef.getDesc(fromId)), toFieldName)
                }

                for (methodDef in classDef.methods) {
                    val fromMethodName = methodDef.getName(fromId)
                    val toMethodName = methodDef.getName(toId)

                    if (fromMethodName == null) {
                        if (project.logger.isDebugEnabled) project.logger.debug("[Unimined/MappingsProvider] Target method {} has no name in src namespace {}", methodDef, srcName)
                        continue
                    }

                    if (toMethodName == null) {
                        if (project.logger.isDebugEnabled) project.logger.debug("[Unimined/MappingsProvider] Target method {} has no name in dst namespace {}", methodDef, dstName)
                        continue
                    }

                    val method = memberOf(fromClassName, fromMethodName, methodDef.getDesc(fromId)!!)

                    acceptor.acceptMethod(method, toMethodName)

                    if (remapLocals) {
                        for (arg in methodDef.args) {
                            val toArgName = arg.getName(toId)

                            if (toArgName != null) {
                                acceptor.acceptMethodArg(method, arg.lvIndex, toArgName)
                            }
                        }

                        for (localVar in methodDef.vars) {
                            val toLocalVarName = localVar.getName(toId)

                            if (toLocalVarName != null) {
                                acceptor.acceptMethodVar(
                                    method,
                                    localVar.lvIndex,
                                    localVar.startOpIdx,
                                    localVar.lvtRowIndex,
                                    toLocalVarName
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
