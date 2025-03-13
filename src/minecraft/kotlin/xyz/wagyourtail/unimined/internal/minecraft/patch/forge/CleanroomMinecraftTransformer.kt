package xyz.wagyourtail.unimined.internal.minecraft.patch.forge

import com.google.gson.JsonObject
import org.apache.commons.lang3.ArchUtils
import org.apache.commons.lang3.SystemUtils
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.artifacts.Dependency
import org.jetbrains.annotations.ApiStatus
import xyz.wagyourtail.unimined.api.mapping.task.ExportMappingsTask
import xyz.wagyourtail.unimined.api.minecraft.patch.forge.CleanroomPatcher
import xyz.wagyourtail.unimined.api.runs.RunConfig
import xyz.wagyourtail.unimined.api.unimined
import xyz.wagyourtail.unimined.internal.mapping.task.ExportMappingsTaskImpl
import xyz.wagyourtail.unimined.internal.minecraft.MinecraftProvider
import xyz.wagyourtail.unimined.internal.minecraft.patch.forge.fg3.FG3MinecraftTransformer
import xyz.wagyourtail.unimined.internal.minecraft.patch.jarmod.JarModMinecraftTransformer
import xyz.wagyourtail.unimined.internal.minecraft.resolver.Library
import xyz.wagyourtail.unimined.internal.minecraft.resolver.parseAllLibraries
import xyz.wagyourtail.unimined.util.FinalizeOnRead
import xyz.wagyourtail.unimined.util.LazyMutable
import xyz.wagyourtail.unimined.util.getFiles
import java.io.File
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.createDirectories

open class CleanroomMinecraftTransformer(project: Project, provider: MinecraftProvider) : ForgeLikeMinecraftTransformer(project, provider, "Cleanroom"),
    CleanroomPatcher<JarModMinecraftTransformer> {

    override var forgeTransformer: JarModMinecraftTransformer by FinalizeOnRead(CleanroomFG3(project, this))

    init {
        accessTransformerTransformer.dependency = project.dependencies.create("net.minecraftforge:accesstransformers:8.1.6")
        accessTransformerTransformer.atMainClass = "net.minecraftforge.accesstransformer.TransformerProcessor"
    }

    @get:ApiStatus.Internal
    @set:ApiStatus.Experimental
    var srgToMCPAsTSRG: Path by FinalizeOnRead(LazyMutable {
        provider.localCache.resolve("mappings").createDirectories().resolve(provider.mappings.combinedNames).resolve("srg2mcp.tsrg").apply {
            val export = ExportMappingsTaskImpl.ExportImpl(provider.mappings).apply {
                location = toFile()
                type = ExportMappingsTask.MappingExportTypes.TSRG_V1
                sourceNamespace = provider.mappings.getNamespace("searge")
                targetNamespace = setOf(provider.mappings.devNamespace)
            }
            export.validate()
            export.exportFunc(provider.mappings.mappingTree)
        }
    })

    private val lwjglClassifier = getLwjglClassifier()

    private val vanillaExcludesSet = setOf(
        "com.mojang:patchy:1.3.9",
        "oshi-project:oshi-core:1.1",
        "net.java.dev.jna:jna:4.4.0",
        "net.java.dev.jna:platform:3.4.0",
        "com.ibm.icu:icu4j-core-mojang:51.2",
        "net.sf.jopt-simple:jopt-simple:5.0.3",
        "io.netty:netty-all:4.1.9.Final",
        "com.google.guava:guava:21.0",
        "org.apache.commons:commons-lang3:3.5",
        "commons-io:commons-io:2.5",
        "commons-codec:commons-codec:1.10",
        "com.google.code.gson:gson:2.8.0",
        "org.apache.commons:commons-compress:1.8.1",
        "org.apache.httpcomponents:httpclient:4.3.3",
        "commons-logging:commons-logging:1.1.3",
        "org.apache.httpcomponents:httpcore:4.3.2",
        "it.unimi.dsi:fastutil:7.1.0",
        "org.apache.logging.log4j:log4j-api:2.17.1",
        "org.apache.logging.log4j:log4j-core:2.17.1",
        "ca.weblite:java-objc-bridge:1.0.0",
        "net.java.jinput:jinput-platform:2.0.5",
        "net.java.jinput:jinput:2.0.5",
        "net.java.jutils:jutils:1.0.0",
        "org.lwjgl.lwjgl:lwjgl-platform:2.9.4-nightly-20150209",
        "org.lwjgl.lwjgl:lwjgl:2.9.4-nightly-20150209",
        "org.lwjgl.lwjgl:lwjgl_util:2.9.4-nightly-20150209"
    )

    override fun addMavens() {
        project.unimined.cleanroomRepos()
        project.unimined.outlandsMaven()
        project.unimined.minecraftForgeMaven()
    }

    override fun loader(dep: Any, action: Dependency.() -> Unit) {
        forge.dependencies.add(if (dep is String && !dep.contains(":")) {
            if (provider.version != "1.12.2") {
                throw IllegalStateException("Cleanroom only supports 1.12.2")
            }
            project.dependencies.create("com.cleanroommc:cleanroom:${dep}:universal@jar")
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

        if (forgeDep.group != "com.cleanroommc" || forgeDep.name != "cleanroom") {
            throw IllegalStateException("Invalid cleanroom dependency found, if you are using multiple dependencies in the forge configuration, make sure the last one is the forge dependency!")
        }
    }

    override val versionJsonJar: File by lazy {
        val forgeDep = forge.dependencies.first()

        val deps = project.configurations.detachedConfiguration()
        val dependency = project.dependencies.create("${forgeDep.group}:${forgeDep.name}:${forgeDep.version}:installer@jar")
        deps.dependencies.add(dependency)
        deps.getFiles(dependency).singleFile
    }

    override fun parseVersionJson(json: JsonObject) {
        val libraries = parseAllLibraries(json.getAsJsonArray("libraries"))
        mainClass = json.get("mainClass").asString
        val args = json.get("minecraftArguments").asString
        provider.addLibraries(libraries.filter {
            !it.name.startsWith("com.cleanroommc:cleanroom:")
        })
        tweakClassClient = args.split("--tweakClass")[1].trim()
    }

    override fun libraryFilter(library: Library): Library? {
        val name = library.name
        if (vanillaExcludesSet.contains(name)) {
            return null
        }
        if (name.startsWith("org.lwjgl") && name.split(":").size > 3) {
            if (!name.endsWith(lwjglClassifier)) {
                return null
            }
        }
        return super.libraryFilter(library)
    }

    override fun applyClientRunTransform(config: RunConfig) {
        super.applyClientRunTransform(config)
        config.properties["mcp_to_srg"] = {
            srgToMCPAsTSRG.absolutePathString()
        }
        config.javaVersion = JavaVersion.VERSION_21
    }

    override fun applyServerRunTransform(config: RunConfig) {
        super.applyServerRunTransform(config)
        config.properties["mcp_to_srg"] = {
            srgToMCPAsTSRG.absolutePathString()
        }
        config.javaVersion = JavaVersion.VERSION_21
    }

    private fun getLwjglClassifier(): String {
        val processor = ArchUtils.getProcessor()
        var classifier = ""
        if (SystemUtils.IS_OS_WINDOWS) {
            classifier += "windows"
            if (processor.isAarch64) {
                classifier += "-arm64"
            } else if (processor.is32Bit) {
                classifier += "-x86"
            }
        } else if (SystemUtils.IS_OS_LINUX) {
            classifier += "linux"
            if (processor.isAarch64) {
                classifier += "-arm64"
            } else if (processor.isRISCV) {
                classifier += "-riscv64"
            } else if (processor.isPPC) {
                classifier += "-ppc64le"
            } else if (!processor.isX86) {
                classifier += "-arm32"
            }
        } else if (SystemUtils.IS_OS_MAC) {
            classifier += "macos"
            if (!processor.isX86) {
                classifier += "-arm64"
            }
        } else if (SystemUtils.IS_OS_FREE_BSD) {
            classifier = "freebsd"
        }
        return classifier
    }

    class CleanroomFG3(project: Project, parent: CleanroomMinecraftTransformer): FG3MinecraftTransformer(project, parent) {

        // override binpatches.pack.lzma meaning it's `userdev3`
        override val userdevClassifier: String = "userdev"

    }


}
