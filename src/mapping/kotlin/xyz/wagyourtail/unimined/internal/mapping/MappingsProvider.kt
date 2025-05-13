package xyz.wagyourtail.unimined.internal.mapping

import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import net.fabricmc.tinyremapper.IMappingProvider
import okio.BufferedSource
import okio.buffer
import okio.source
import okio.use
import org.gradle.api.Project
import org.gradle.api.artifacts.Dependency
import xyz.wagyourtail.commonskt.reader.StringCharReader
import xyz.wagyourtail.unimined.api.UniminedExtension
import xyz.wagyourtail.unimined.api.mapping.MappingsConfig
import xyz.wagyourtail.unimined.api.mapping.dsl.MappingDSL
import xyz.wagyourtail.unimined.api.mapping.dsl.MemoryMapping
import xyz.wagyourtail.unimined.api.minecraft.MinecraftConfig
import xyz.wagyourtail.unimined.api.unimined
import xyz.wagyourtail.unimined.mapping.EnvType
import xyz.wagyourtail.unimined.mapping.Namespace
import xyz.wagyourtail.unimined.mapping.formats.mcp.v3.MCPv3ClassesReader
import xyz.wagyourtail.unimined.mapping.formats.mcp.v3.MCPv3FieldReader
import xyz.wagyourtail.unimined.mapping.formats.mcp.v3.MCPv3MethodReader
import xyz.wagyourtail.unimined.mapping.formats.rgs.RetroguardReader
import xyz.wagyourtail.unimined.mapping.formats.srg.SrgReader
import xyz.wagyourtail.unimined.mapping.formats.umf.UMFReader
import xyz.wagyourtail.unimined.mapping.formats.umf.UMFWriter
import xyz.wagyourtail.unimined.mapping.jvms.four.three.three.MethodDescriptor
import xyz.wagyourtail.unimined.mapping.jvms.four.three.two.FieldDescriptor
import xyz.wagyourtail.unimined.mapping.jvms.four.two.one.InternalName
import xyz.wagyourtail.unimined.mapping.propagator.CachedInheritanceTree
import xyz.wagyourtail.unimined.mapping.propagator.InheritanceTree
import xyz.wagyourtail.unimined.mapping.propogator.Propagator
import xyz.wagyourtail.unimined.mapping.resolver.ContentProvider
import xyz.wagyourtail.unimined.mapping.resolver.MappingResolver
import xyz.wagyourtail.unimined.mapping.tree.AbstractMappingTree
import xyz.wagyourtail.unimined.mapping.tree.LazyMappingTree
import xyz.wagyourtail.unimined.mapping.tree.MemoryMappingTree
import xyz.wagyourtail.unimined.mapping.util.Scoped
import xyz.wagyourtail.unimined.mapping.visitor.*
import xyz.wagyourtail.unimined.mapping.visitor.delegate.Delegator
import xyz.wagyourtail.unimined.mapping.visitor.delegate.delegator
import xyz.wagyourtail.unimined.util.*
import java.io.File
import java.net.URI
import java.nio.file.StandardOpenOption
import kotlin.collections.toMutableMap
import kotlin.io.path.*

@Scoped
open class MappingsProvider(project: Project, minecraft: MinecraftConfig, subKey: String? = null) : MappingsConfig<MappingsProvider>(project, minecraft, subKey) {
    val unimined: UniminedExtension = project.unimined

    override fun createForPostProcess(key: String, action: MemoryMappingTree.() -> Unit): MappingsProvider {
        return object : MappingsProvider(project, minecraft, key) {

            override suspend fun afterLoad(tree: MemoryMappingTree) {
                super.afterLoad(tree)
                tree.action()
            }

        }
    }

    val mappings = project.configurations.detachedConfiguration()

    private var stubMappings: LazyMappingTree? = null

    override var legacyFabricGenVersion by FinalizeOnRead(1)
    override var ornitheGenVersion by FinalizeOnRead(1)

    var splitUnmapped by FinalizeOnRead(LazyMutable {
        minecraft.minecraftData.mcVersionCompare(minecraft.version, "1.3") < 0
    })

    var lazyConfigure: MappingsConfig<*>.() -> Unit = {}

    override var envType: EnvType by LazyMutable {
        minecraft.side
    }

    override val unmappedNs: Set<Namespace> by lazy {
        if (splitUnmapped && envType == EnvType.JOINED) {
            setOf(Namespace("clientOfficial"), Namespace("serverOfficial"))
        } else {
            setOf(Namespace("official"))
        }
    }

    override suspend fun applyInheritanceTree(tree: MemoryMappingTree, apply: suspend (InheritanceTree) -> Unit) {
        if (splitUnmapped && envType == EnvType.JOINED) {
            val clientPropagator = Propagator(
                tree,
                Namespace("clientOfficial"),
                setOf(minecraft.minecraftData.minecraftClientFile.toPath())
            )

//            writeInheritanceTree(envType.name, clientPropagator)

            apply(clientPropagator)

            val serverPropagator = Propagator(
                tree,
                Namespace("serverOfficial"),
                setOf(minecraft.minecraftData.minecraftServerFile.toPath())
            )

//            writeInheritanceTree(envType.name, serverPropagator)

            apply(serverPropagator)
        } else {
            val propagator = Propagator(
                tree, Namespace("official"), setOf(
                    when (envType) {
                        EnvType.JOINED -> minecraft.mergedOfficialMinecraftFile
                        EnvType.CLIENT -> minecraft.minecraftData.minecraftClientFile
                        EnvType.SERVER -> minecraft.minecraftData.minecraftServerFile
                    }!!.toPath()
                )
            )

//            writeInheritanceTree(envType.name, propagator)

            apply(propagator)
        }
    }

    private fun legacyFabricRevisionTransform(mavenCoords: MavenCoords): MavenCoords {
        if (legacyFabricGenVersion < 2) {
            return mavenCoords
        }
        return MavenCoords("${mavenCoords.group}.v${legacyFabricGenVersion}", mavenCoords.artifact, mavenCoords.version, mavenCoords.classifier, mavenCoords.extension)
    }

    private fun ornitheGenRevisionTransform(mavenCoords: MavenCoords): MavenCoords {
        if (ornitheGenVersion < 2) {
            return mavenCoords
        }
        return MavenCoords(mavenCoords.group!!, "${mavenCoords.artifact}-gen$ornitheGenVersion", mavenCoords.version, mavenCoords.classifier, mavenCoords.extension)
    }


    override fun intermediary() {
        unimined.fabricMaven()
        addDependency("intermediary", MappingEntry(contentOf(
            MavenCoords(
                "net.fabricmc",
                "intermediary",
                minecraft.version,
                "v2"
            )
        ), "intermediary").apply {
            provides("intermediary" to false)
        })
    }

    override fun calamus() {
        unimined.ornitheMaven()
        val environment = if (splitUnmapped && ornitheGenVersion < 2) {
            when (envType) {
                EnvType.CLIENT -> "-client"
                EnvType.SERVER -> "-server"
                else -> throw IllegalStateException("Cannot use Calamus on Minecraft ${minecraft.version} with side COMBINED")
            }
        } else {
            ""
        }
        addDependency("calamus", MappingEntry(
            contentOf(ornitheGenRevisionTransform(
                MavenCoords(
                "net.ornithemc",
                "calamus-intermediary",
                minecraft.version + environment,
                "v2"
            )
            )),
            "calamus"
        ).apply {
            provides("calamus" to false)
            mapNamespace("intermediary", "calamus")
            if (splitUnmapped && ornitheGenVersion > 1) {
                when (envType) {
                    EnvType.CLIENT -> {
                        mapNamespace("clientOfficial", "official")
                    }
                    EnvType.SERVER -> {
                        mapNamespace("serverOfficial", "official")
                    }
                    EnvType.JOINED -> {
                        provides("serverOfficial" to false)
                        requires("clientOfficial")
                    }
                }
            }
        })
    }

    override fun legacyIntermediary() {
        unimined.legacyFabricMaven()
        addDependency("legacyIntermediary", MappingEntry(
            contentOf(legacyFabricRevisionTransform(
                MavenCoords(
                "net.legacyfabric",
                "intermediary",
                minecraft.version,
                "v2"
            )
            )),
            "legacyIntermediary"
        ).apply {
            provides("legacyIntermediary" to false)
            mapNamespace("intermediary", "legacyIntermediary")
        })
    }


    override fun babricIntermediary() {
        unimined.glassLauncherMaven("babric")
        addDependency("babricIntermediary", MappingEntry(contentOf(MavenCoords("babric", "intermediary", minecraft.version, "v2")), "babricIntermediary").apply {
            provides("babricIntermediary" to false)
            when (envType) {
                EnvType.CLIENT -> {
                    mapNamespace("client", "official")
                    mapNamespace("clientOfficial", "official")
                }
                EnvType.SERVER -> {
                    mapNamespace("server", "official")
                    mapNamespace("serverOfficial", "official")
                }
                EnvType.JOINED -> {
                    mapNamespace("client", "clientOfficial")
                    mapNamespace("server", "serverOfficial")
                    provides("serverOfficial" to false)
                    requires("clientOfficial")
                }
            }
            mapNamespace("intermediary", "babricIntermediary")
        })
    }


    override fun searge(version: String) {
        unimined.minecraftForgeMaven()
        val mappings = if (minecraft.minecraftData.mcVersionCompare(minecraft.version, "1.12.2") < 0) {
            MavenCoords("de.oceanlabs.mcp", "mcp", version, "srg", "zip")
        } else {
            MavenCoords("de.oceanlabs.mcp", "mcp_config", version, null,"zip")
        }
        if (minecraft.minecraftData.mcVersionCompare(minecraft.version, "1.16.5") > 0) {
            postProcessDependency("searge", {
                mojmap()
                addDependency("searge", MappingEntry(contentOf(mappings), "searge-$version").apply {
                    mapNamespace("obf" to "official")
                    provides("srg" to false)
                })
            }, {
                val searge = Namespace("searge")
                val mojmap = Namespace("mojmap")

                accept(delegator(object: Delegator() {
                    override fun visitClass(
                        delegate: MappingVisitor,
                        names: Map<Namespace, InternalName>
                    ): ClassVisitor? {
                        return if (mojmap in names) {
                            super.visitClass(delegate, names + (searge to names[mojmap]!!))
                        } else {
                            super.visitClass(delegate, names)
                        }
                    }
                }))
                runBlocking {
                    fillMissingNames(Namespace("srg") to setOf(Namespace("searge")))
                }
            }) {
                provides("searge" to false)
            }
        } else {
            addDependency("searge", MappingEntry(contentOf(mappings), "searge-$version").apply {
                provides("searge" to false)
                mapNamespace("source" to "official", "target" to "searge")
            })
        }
    }


    override fun mojmap() {
        val mappings = when (envType) {
            EnvType.CLIENT, EnvType.JOINED -> "client"
            EnvType.SERVER -> "server"
        }
        addDependency("mojmap", MappingEntry(contentOf(
            MavenCoords(
                "net.minecraft",
                "$mappings-mappings",
                minecraft.version,
                null,
                "txt"
            )),
            "mojmap"
        ).apply {
            mapNamespace("source" to "mojmap", "target" to "official")
            provides("mojmap" to true)
        })
    }


    override fun mcp(channel: String, version: String) {
        if (channel == "legacy") {
            unimined.wagYourMaven("releases")
        } else {
            unimined.minecraftForgeMaven()
        }
        if (envType == EnvType.JOINED && splitUnmapped) throw UnsupportedOperationException("MCP mappings are not supported in joined environments before 1.3")
        val mappings = "de.oceanlabs.mcp:mcp_${channel}:${version}@zip"
        addDependency("mcp", MappingEntry(contentOf(mappings), "mcp-$channel-$version").apply {
            subEntry { _, format ->
                when (format.reader) {
                    is RetroguardReader, SrgReader -> {
                        mapNamespace("source" to "official", "target" to "searge")
                        provides("searge" to false)
                    }
                    is MCPv3ClassesReader, MCPv3FieldReader, MCPv3MethodReader -> {
                        mapNamespace("notch" to "official")
                        provides("searge" to false, "mcp" to true)
                        if (format.reader is MCPv3ClassesReader) {
                            insertInto.add {
                                it.delegator(object : Delegator() {
                                    override fun visitClass(
                                        delegate: MappingVisitor,
                                        names: Map<Namespace, InternalName>
                                    ): ClassVisitor? {
                                        val names = names.toMutableMap()
                                        val srgName = names[Namespace("searge")]
                                        if (srgName != null) {
                                            names[Namespace("mcp")] = srgName
                                        }
                                        return super.visitClass(delegate, names)
                                    }
                                })
                            }
                        }
                    }
                     else -> {
                         requires("searge")
                         provides("mcp" to true)
                     }
                }
            }
        })
    }


    override fun retroMCP(version: String) {
        unimined.mcphackersIvy()
        addDependency("retroMCP", MappingEntry(contentOf(MavenCoords("io.github.mcphackers", "mcp", version, extension = "zip")), "retroMCP-$version").apply {
            mapNamespace("named" to "retroMCP")
            if (splitUnmapped) {
                when (envType) {
                    EnvType.CLIENT -> {
                        mapNamespace("client", "official")
                        mapNamespace("clientOfficial", "official")
                    }

                    EnvType.SERVER -> {
                        mapNamespace("server", "official")
                        mapNamespace("serverOfficial", "official")
                    }

                    EnvType.JOINED -> {
                        mapNamespace("client", "clientOfficial")
                        mapNamespace("server", "serverOfficial")
                        requires("clientOfficial")
                        provides("serverOfficial" to false)
                    }
                }
            }
            provides("retroMCP" to true)
        })
    }

    override fun unknownThingy(version: String, format: String) {
        if (!minecraft.version.startsWith("1.4"))
            throw UnsupportedOperationException("Unknown Thingy is only supported for Minecraft 1.4")
        unimined.sleepingTownMaven()
        val entry = MappingEntry(contentOf(
            when (format.lowercase()) {
                // TinyV1 were GZip-only
//                "tiny", "tinyV1" -> MavenCoords("com.unascribed", "unknownthingy", version, extension = "gz")
                "tinyv2" -> MavenCoords("com.unascribed", "unknownthingy", version, "v2")
                "tsrg2" -> MavenCoords("com.unascribed", "unknownthingy", version, extension = "tsrg2")
                else -> MavenCoords("com.unascribed", "unknownthingy", version, extension = "tsrg")
            }
        ), "unknownThingy-$version").apply {
            when (format.lowercase()) {
                "tiny", "tinyv1", "tinyv2" -> mapNamespace("named" to "unknownThingy")
                "tsrg2" -> mapNamespace("obf" to "official", "srg" to "unknownThingy")
                else -> mapNamespace("source" to "official", "target" to "unknownThingy")

            }

            provides("unknownThingy" to true)
            renest()
        }
        addDependency("unknownThingy", entry)
    }


    override fun yarn(build: Int) {
        unimined.fabricMaven()
        val entry = MappingEntry(contentOf(
            MavenCoords(
            "net.fabricmc",
            "yarn",
            minecraft.version + "+build.$build",
            "v2"
        )), "yarn-$build"
        ).apply {
            requires("intermediary")
            mapNamespace("named" to "yarn")
            provides("yarn" to true)
            renest()
        }
        addDependency("yarn", entry)
    }

    override fun yarnv1(build: Int) {
        unimined.fabricMaven()
        val yarn = project.configurations.detachedConfiguration(project.dependencies.create("net.fabricmc:yarn:${minecraft.version}+build.${build}")).resolve()
        val jar = yarn.first { it.extension == "jar" }
        val v1Folder = unimined.getGlobalCache().resolve("yarnv1").createDirectories()
        val temp = v1Folder.resolve("yarnv1-${minecraft.version}+build.$build.tiny")
        jar.toPath().readZipInputStreamFor("mappings/mappings.tiny") {
            it.copyTo(temp.outputStream(StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING))
        }
        val output = v1Folder.resolve("yarnv1-${minecraft.version}+build.$build-filled.tiny")

        project.javaexec {
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

        mapping(output.toFile(), "yarnv1-$build") {
            provides("yarnv1" to true)
            mapNamespace("named" to "yarnv1")
            requires("intermediary")
            renest()
        }
    }


    override fun feather(build: Int) {
        unimined.ornitheMaven()
        val vers = if (splitUnmapped && ornitheGenVersion < 2) {
            if (envType == EnvType.JOINED) throw UnsupportedOperationException("Feather mappings are not supported in joined environments before 1.3")
            "${minecraft.version}-${envType.name.lowercase()}+build.$build"
        } else {
            "${minecraft.version}+build.$build"
        }
        val entry = MappingEntry(
            contentOf(ornitheGenRevisionTransform(
                MavenCoords(
                    "net.ornithemc",
                    "feather",
                    vers,
                    "v2"
                ))), "feather-$build"
        ).apply {
            requires("calamus")
            provides("feather" to true)
            mapNamespace("intermediary" to "calamus", "named" to "feather")
            renest()
            insertInto.add {
                it.delegator(object: Delegator() {
                    val calamus = Namespace("calamus")
                    val feather = Namespace("feather")

                    override fun visitClass(
                        delegate: MappingVisitor,
                        names: Map<Namespace, InternalName>
                    ): ClassVisitor? {
                        return if (feather in names) {
                            super.visitClass(
                                delegate,
                                names + (feather to InternalName.unchecked(
                                    names[feather]!!.toString().replace("__", "$")
                                ))
                            )
                        } else {
                            super.visitClass(delegate, names)
                        }
                    }

                })
            }
        }
        addDependency("feather", entry)
    }


    override fun legacyYarn(build: Int) {
        unimined.legacyFabricMaven()
        val entry = MappingEntry(
            contentOf(legacyFabricRevisionTransform(
                MavenCoords(
                "net.legacyfabric",
                "yarn",
                "${minecraft.version}+build.$build",
                "v2"
            ))), "legacyYarn-$build"
        ).apply {
            requires("legacyIntermediary")
            provides("legacyYarn" to true)
            mapNamespace("intermediary" to "legacyIntermediary", "named" to "legacyYarn")
            renest()
        }
        addDependency("legacyYarn", entry)
    }


    override fun barn(build: Int) {
        unimined.glassLauncherMaven("babric")
        val entry = MappingEntry(contentOf(MavenCoords(
            "babric",
            "barn",
            "${minecraft.version}+build.$build", "v2")
        ), "barn-$build").apply {
            requires("babricIntermediary")
            provides("barn" to true)
            mapNamespace("intermediary" to "babricIntermediary", "named" to "barn")
            renest()
        }
        addDependency("barn", entry)
    }

    override fun biny(commitName: String) {
        unimined.glassLauncherMaven("releases")
        val entry = MappingEntry(contentOf(
            MavenCoords("net.glasslauncher", "biny", "${minecraft.version}+$commitName", "v2")
        ), "biny-$commitName").apply {
            requires("babricIntermediary")
            provides("biny" to true)
            mapNamespace("intermediary" to "babricIntermediary", "named" to "biny")
            renest()
        }
        addDependency("biny", entry)
    }

    override fun nostalgia(build: Int) {
        unimined.wispForestMaven()
        val entry = MappingEntry(contentOf(
            MavenCoords(
                "me.alphamode",
                "nostalgia",
                "${minecraft.version}+build.$build",
                "v2"
            )), "nostalgia-$build"
        ).apply {
            requires("babricIntermediary")
            provides("nostalgia" to true)
            mapNamespace("intermediary" to "babricIntermediary", "named" to "nostalgia")
            renest()
        }
        addDependency("nostalgia", entry)
    }

    override fun quilt(build: Int) {
        unimined.quiltMaven()
        val entry = MappingEntry(contentOf(
            MavenCoords(
                "org.quiltmc",
                "quilt-mappings",
            "${minecraft.version}+build.$build",
                "intermediary-v2"
            )), "quilt-$build"
        ).apply {
            requires("intermediary")
            provides("quilt" to true)
            renest()
        }
        addDependency("quilt", entry)
    }


    override fun forgeBuiltinMCP(version: String) {
        val mcVer = if (minecraft.version == "1.4") "1.4.0" else minecraft.version
        unimined.minecraftForgeMaven()
        addDependency("forgeBuiltinMCP", MappingEntry(contentOf(
            MavenCoords(
                "net.minecraftforge",
                "forge",
                "${mcVer}-$version",
                "src",
                "zip"
            )), "forgeBuiltinMCP-$version"
        ).apply {
            subEntry {_, format ->
                when (format.reader) {
                    is SrgReader -> {
                        mapNamespace("source" to "official", "target" to "searge")
                        provides("searge" to false)
                    }
                    else -> {
                        requires("searge")
                        mapNamespace("mcp" to "forgeMCP")
                        provides("forgeMCP" to true)
                    }
                }
            }
        })
    }

    override fun parchment(
        mcVersion: String,
        version: String,
        checked: Boolean,
    ) {
        unimined.parchmentMaven()
        addDependency("parchment", MappingEntry(contentOf(
            MavenCoords(
                "org.parchmentmc.data",
                "parchment-$mcVersion",
                version,
                if (checked) "checked" else null,
                "zip"
            )), "parchment-$version"
        ).apply {
            requires("mojmap")
            mapNamespace("source" to "mojmap")
            provides("mojmap" to true)
        })
    }

    override fun spigotDev(mcVersion: String) {
        val buildInfo = project.cachingDownload(
            URI.create("https://hub.spigotmc.org/versions/${mcVersion}.json"),
            cachePath = unimined.getGlobalCache().resolve("spigot/$mcVersion/build-info.json")
        ).let {
            JsonParser.parseString(it.readText()).asJsonObject
        }

        val version = buildInfo["refs"].asJsonObject["BuildData"].asString
        val buildData = project.cachingDownload(
            URI.create("https://hub.spigotmc.org/stash/rest/api/latest/projects/SPIGOT/repos/builddata/archive?at=$version&format=zip"),
            cachePath = unimined.getGlobalCache().resolve("spigot/$mcVersion/build-data.zip")
        )

        val (layerMojmap, hasMembers) = buildData.readZipInputStreamFor("info.json") { ifs ->
            val info = ifs.reader().use { JsonParser.parseReader(it).asJsonObject }

            info.has("mappingsUrl") to info.has("memberMappings")
        }

        if (layerMojmap) {
            project.logger.lifecycle("[Unimined/MappingsProvider] Layering mojmap on top of spigot")
            postProcessDependency("spigotDev", {
                mojmap()
                mapping(buildData.toFile(), "spigotDev") {
                    mapNamespace("target", "spigotDev")
                    mapNamespace("source", "official")
                    provides("spigotDev" to true)
                    requires("mojmap")

                    renest()
                }
            }) {
                insertInto.add {

                    it.delegator(object : Delegator() {
                        val spigot = Namespace("spigotDev")
                        val mojmap = Namespace("mojmap")

                        override fun visitField(
                            delegate: ClassVisitor,
                            names: Map<Namespace, Pair<String, FieldDescriptor?>>
                        ): FieldVisitor? {
                            val names = names.toMutableMap()
                            if (mojmap in names) {
                                names[spigot] = names[mojmap]!!
                            }
                            return super.visitField(delegate, names)
                        }

                        override fun visitMethod(
                            delegate: ClassVisitor,
                            names: Map<Namespace, Pair<String, MethodDescriptor?>>
                        ): MethodVisitor? {
                            if (hasMembers) {
                                return super.visitMethod(delegate, names)
                            }
                            val names = names.toMutableMap()
                            if (mojmap in names) {
                                names[spigot] = names[mojmap]!!
                            }
                            return super.visitMethod(delegate, names)
                        }
                    })

                }
            }
        } else {
            mapping(buildData.toFile(), "spigotDev") {
                mapNamespace("source", "official")
                mapNamespace("target", "spigotDev")
                provides("spigotDev" to true)

                renest()
            }
        }
    }

    override fun mapping(
        dependency: String,
        key: String,
        action: @Scoped (MappingResolver<MappingsProvider>.MappingEntry.() -> Unit)
    ) {
        val coords = MavenCoords(dependency)
        addDependency(key, MappingEntry(contentOf(coords), "$key-${coords.version}").apply {
            action()
        })
    }

    override fun mapping(
        dependency: MavenCoords,
        key: String,
        action: @Scoped (MappingResolver<MappingsProvider>.MappingEntry.() -> Unit)
    ) {
        addDependency(key, MappingEntry(contentOf(dependency), "$key-${dependency.version}").apply {
            action()
        })
    }

    override fun mapping(
        dependency: File,
        key: String,
        action: @Scoped (MappingResolver<MappingsProvider>.MappingEntry.() -> Unit)
    ) {
        addDependency(key, MappingEntry(contentOf(dependency), key).apply {
            action()
        })
    }

    fun contentOf(coords: MavenCoords): ContentProvider {
        val dep = project.dependencies.create(coords.toString())
        mappings.dependencies.add(dep)
        return MappingContentProvider(dep, coords.extension)
    }

    fun contentOf(coords: String): ContentProvider {
        return contentOf(MavenCoords(coords))
    }

    fun contentOf(file: File): ContentProvider {
        val dep = project.dependencies.create(project.files(file))
        mappings.dependencies.add(dep)
        return MappingContentProvider(dep, file.extension)
    }

    override suspend fun afterLoad(tree: MemoryMappingTree) {
        if (stubMappings != null) {
            stubMappings!!.lazyAccept(tree)
        }
        super.afterLoad(tree)
    }

    override fun hasStubs(): Boolean {
        return stubMappings != null
    }

    override suspend fun combinedNames(): String = buildString {
        append(super.combinedNames())
        if (hasStubs()) {
            append("-stubs-")
            append(buildString { stubMappings!!.lazyAccept(UMFWriter.write(::append)) }.getShortSha1())
        }
    }

    @Deprecated("Use stubs instead", replaceWith = ReplaceWith("stubs(*namespaces, apply = apply)"))
    override val stub: MemoryMapping
        get() {
            if (stubMappings == null) {
                stubMappings = LazyMappingTree()
            }
            return MemoryMapping(stubMappings!!)
        }

    override fun stubs(vararg namespaces: String, apply: MappingDSL.() -> Unit) {
        if (finalized) {
            throw UnsupportedOperationException("Cannot add stub mappings after finalization")
        }
        if (stubMappings == null) {
            stubMappings = LazyMappingTree()
        }
        MappingDSL(stubMappings!!).apply {
            namespace(*namespaces)
            apply()
        }
    }

    val cacheFolder by lazy {
        if (hasStubs()) {
            unimined.getLocalCache().resolve("mappings/${minecraft.version}")
        } else {
            minecraft.minecraftData.mcVersionFolder.resolve("mappings")
        }
    }

    override suspend fun fromCache(key: String): AbstractMappingTree? {
        val mappingFile = cacheFolder.resolve("mappings-${key}.umf")
        if (!mappingFile.exists() || unimined.forceReload) {
            mappingFile.deleteIfExists()
            return null
        }
        mappingFile.source().buffer().use {
            val lazy = LazyMappingTree()
            UMFReader.read(it, lazy)
            return lazy
        }
    }

    fun readInheritanceTree(key: String, tree: MemoryMappingTree): InheritanceTree? {
        val mappingFile = cacheFolder.resolve("mappings-${key}.umf_it")
        if (!mappingFile.exists() || unimined.forceReload) {
            mappingFile.deleteIfExists()
            return null
        }
        mappingFile.inputStream().use {
            return CachedInheritanceTree(tree, StringCharReader(it.readBytes().toString(Charsets.UTF_8)))
        }
    }

    fun writeInheritanceTree(key: String, tree: InheritanceTree) {
        val mappingFile = cacheFolder.resolve("mappings-${key}.umf_it")
        mappingFile.parent?.createDirectories()
        val tmp = mappingFile.resolveSibling(mappingFile.name + ".tmp")
        tmp.bufferedWriter().use {
            CachedInheritanceTree.write(tree, it::append)
        }
        tmp.moveTo(mappingFile, true)
    }

    override suspend fun writeCache(key: String, tree: AbstractMappingTree) {
        val mappingFile = cacheFolder.resolve("mappings-${key}.umf")
        mappingFile.parent?.createDirectories()
        val tmp = mappingFile.resolveSibling(mappingFile.name + ".tmp")
        tmp.bufferedWriter().use {
            tree.accept(UMFWriter.write(it::append))
        }
        tmp.moveTo(mappingFile, true)
    }

    override fun configure(action: MappingsConfig<*>.() -> Unit) {
        if (finalized) {
            throw UnsupportedOperationException("Cannot configure mappings after finalization")
        }
        val old = lazyConfigure
        lazyConfigure = {
            old()
            action()
        }
    }

    override suspend fun finalize() {
        if (!finalized) {
            lazyConfigure()
        }
        super.finalize()
    }

    override suspend fun getTRMappings(
        remap: Pair<Namespace, Namespace>,
        remapLocals: Boolean,
    ) : (IMappingProvider.MappingAcceptor) -> Unit {
        val mappings = this.resolve()
        return { acceptor ->
            val srcName = remap.first
            val dstName = remap.second

            if (srcName !in mappings.namespaces) {
                throw IllegalArgumentException("Source namespace $srcName not found in mappings")
            }
            if (dstName !in mappings.namespaces) {
                throw IllegalArgumentException("Target namespace $dstName not found in mappings")
            }

            val visitor = EmptyMappingVisitor().delegator(object : Delegator() {
                lateinit var fromClassName: String
                lateinit var toClassName: String
                lateinit var fromMethod: IMappingProvider.Member

                private fun memberOf(className: String, memberName: String, descriptor: String?): IMappingProvider.Member {
                    return IMappingProvider.Member(className, memberName, descriptor)
                }

                override fun visitClass(delegate: MappingVisitor, names: Map<Namespace, InternalName>): ClassVisitor? {
                    if (srcName in names && dstName in names) {
                        fromClassName = names[srcName]!!.toString()
                        toClassName = names[dstName]!!.toString()
                        acceptor.acceptClass(fromClassName, toClassName)
                        return super.visitClass(delegate, names)
                    }
                    return null
                }

                override fun visitMethod(
                    delegate: ClassVisitor,
                    names: Map<Namespace, Pair<String, MethodDescriptor?>>
                ): MethodVisitor? {
                    if (srcName in names && dstName in names) {
                        val fromMethodName = names[srcName]!!.first
                        val fromMethodDesc = names[srcName]!!.second ?: return null
                        val toMethodName = names[dstName]!!.first
                        fromMethod = memberOf(fromClassName, fromMethodName, fromMethodDesc.toString())
                        acceptor.acceptMethod(fromMethod, toMethodName)
                        if (remapLocals) {
                            return super.visitMethod(delegate, names)
                        }
                    }
                    return null
                }

                override fun visitField(
                    delegate: ClassVisitor,
                    names: Map<Namespace, Pair<String, FieldDescriptor?>>
                ): FieldVisitor? {
                    if (srcName in names && dstName in names) {
                        val fromFieldName = names[srcName]!!.first
                        val fromFieldDesc = names[srcName]!!.second ?: return null
                        val toFieldName = names[dstName]!!.first
                        acceptor.acceptField(memberOf(fromClassName, fromFieldName, fromFieldDesc.toString()), toFieldName)
                    }
                    return null
                }

                override fun visitParameter(
                    delegate: InvokableVisitor<*>,
                    index: Int?,
                    lvOrd: Int?,
                    names: Map<Namespace, String>
                ): ParameterVisitor? {
                    if (dstName in names && lvOrd != null) {
                        val toArgName = names[dstName]!!
                        acceptor.acceptMethodArg(fromMethod, lvOrd, toArgName)
                    }
                    return null
                }

                override fun visitLocalVariable(
                    delegate: InvokableVisitor<*>,
                    lvOrd: Int,
                    startOp: Int?,
                    names: Map<Namespace, String>
                ): LocalVariableVisitor? {
                    if (dstName in names) {
                        val toLocalVarName = names[dstName]!!
                        acceptor.acceptMethodVar(fromMethod, lvOrd, startOp ?: -1, -1, toLocalVarName)
                    }
                    return null
                }


            })

            if (mappings is LazyMappingTree) {
                mappings.nonLazyAccept(visitor)
            } else {
                mappings.accept(visitor)
            }
        }
    }

    inner class MappingContentProvider(val dep: Dependency, val ext: String) : ContentProvider {

        override fun content(): BufferedSource {
            return mappings.getFiles(dep) { it.extension == ext }.singleFile.source().buffer()
        }

        override fun fileName(): String {
            return mappings.getFiles(dep) { it.extension == ext }.singleFile.name
        }

        override suspend fun resolve() {
            mappings.resolve()
        }

    }

}
