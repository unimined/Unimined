package xyz.wagyourtail.unimined.internal.minecraft.patch.forge

import com.google.gson.JsonObject
import kotlinx.coroutines.runBlocking
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.artifacts.Dependency
import org.jetbrains.annotations.ApiStatus
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
import xyz.wagyourtail.unimined.mapping.formats.tsrg.TsrgV1Writer
import java.io.File
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.createDirectories

open class CleanroomMinecraftTransformer(project: Project, provider: MinecraftProvider) : ForgeLikeMinecraftTransformer(project, provider, "Cleanroom"),
    CleanroomPatcher<JarModMinecraftTransformer> {

    override var forgeTransformer: JarModMinecraftTransformer by FinalizeOnRead(CleanroomFG3(project, this))

    init {
        atDependency = project.dependencies.create("net.minecraftforge:accesstransformers:8.1.6")
        atMainClass = "net.minecraftforge.accesstransformer.TransformerProcessor"
    }

    @get:ApiStatus.Internal
    @set:ApiStatus.Experimental
    var srgToMCPAsTSRG: Path by FinalizeOnRead(LazyMutable {
        provider.localCache.resolve("mappings").createDirectories().resolve("srg2mcp.tsrg").apply {
            val export = ExportMappingsTaskImpl.ExportImpl(provider.mappings).apply {
                location = toFile()
                type = TsrgV1Writer
                sourceNamespace = prodNamespace
                targetNamespace = setOf(provider.mappings.devNamespace)
            }
            export.validate()
            runBlocking {
                export.exportFunc(provider.mappings.resolve())
            }
        }
    })

    private val vanillaExcludesSet = setOf(
        "com.mojang:patchy:",
        "oshi-project:oshi-core:",
        "com.ibm.icu:icu4j-core-mojang:",
        "net.java.jutils:",
        "org.lwjgl.lwjgl:",
        "io.netty:netty-all:"
    )

    override fun addMavens() {
        project.unimined.cleanroomRepos()
        project.unimined.arcseekersMaven()
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
        tweakClassClient = args.split("--tweakClass")[1].trim().substringBefore(" ")
    }

    override fun libraryFilter(library: Library): Library? {
        if (vanillaExcludesSet.any { library.name.startsWith(it) }) {
            return null
        }
        if (library.name.startsWith("org.lwjgl") && library.name.substringAfterLast(":").startsWith("2")) {
            return null
        }
        if (library.name.startsWith("net.java.dev.jna:platform:")) {
            return null
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

    class CleanroomFG3(project: Project, parent: CleanroomMinecraftTransformer): FG3MinecraftTransformer(project, parent) {

        // override binpatches.pack.lzma meaning it's `userdev3`
        override val userdevClassifier: String = "userdev"

    }


}
