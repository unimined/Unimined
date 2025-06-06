package xyz.wagyourtail.unimined.internal.minecraft.patch.conversion.reindev

import com.google.gson.Gson
import com.google.gson.JsonObject
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.Dependency
import org.gradle.jvm.tasks.Jar
import xyz.wagyourtail.unimined.api.minecraft.MinecraftJar
import xyz.wagyourtail.unimined.api.minecraft.patch.conversion.reindev.FoxLoaderPatcher
import xyz.wagyourtail.unimined.api.runs.RunConfig
import xyz.wagyourtail.unimined.api.unimined
import xyz.wagyourtail.unimined.mapping.EnvType
import xyz.wagyourtail.unimined.util.*
import java.io.InputStreamReader
import kotlin.io.path.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.inputStream

class FoxLoaderMinecraftTransformer(
    project: Project,
    provider: ReIndevProvider,
    providerName: String = "ReIndev-FoxLoader"
) : AbstractReIndevTransformer(project, provider, providerName), FoxLoaderPatcher {

    val foxLoaderVersions: Map<String, String> by lazy {
        project.cachingDownload("https://cdn.fox2code.com/maven/foxloader-version-map.json").inputStream().use {
            Gson().fromJson(InputStreamReader(it), JsonObject::class.java).asMap().map { entry ->
                entry.key to entry.value.asString
            }.toMap()
        }
    }

    init {
        project.unimined.modrinthMaven()
        project.unimined.jitpack()
    }

    private val isClient by lazy {
        provider.side == EnvType.CLIENT || provider.side == EnvType.JOINED
    }
    private val isServer by lazy {
        provider.side == EnvType.SERVER || provider.side == EnvType.JOINED
    }

    private val foxLoader: Configuration by lazy {
        project.configurations.maybeCreate("foxLoader".withSourceSet(provider.sourceSet)).also {
            provider.minecraftLibraries.extendsFrom(it)
        }
    }

    private val foxLoaderInvoker: Configuration by lazy {
        project.configurations.maybeCreate("foxLoaderInvoker")
    }

    val foxLoaderCompatibleVersion by lazy {
        provider.version.replaceFirst(Regex("_0*"), "_")
    }

    override fun loader() {
        foxLoaderVersions.filter {
            it.value == foxLoaderCompatibleVersion
        }.keys.reduce { prev, curr ->
            if (curr.compareFlexVer(prev) > 0) curr else prev
        }.also { version ->
            loader(version)
        }
    }

    override fun loader(dep: Any, action: Dependency.() -> Unit) {
        foxLoaderInvoker.dependencies.add(project.dependencies.create("com.fox2code:FoxLoaderInvoker:1.1.0"))

        if (!foxLoaderVersions.containsValue(foxLoaderCompatibleVersion)) {
            project.logger.error("Incompatible FoxLoader $dep for ReIndev $foxLoaderCompatibleVersion")
            project.logger.error("FoxLoader valid version pairs: $foxLoaderVersions")
            throw RuntimeException(IllegalArgumentException("FoxLoader version is not compatible with ReIndev version"))
        }

        val foxLoaderDeps: List<Dependency>
        if (!canCombine) {
            if (provider.side == EnvType.JOINED) throw IllegalStateException("Cannot use FoxLoader pre-2.9 without specifying non-joined side")
            foxLoaderDeps = listOf(
                project.dependencies.create("com.fox2code.FoxLoader:common:${dep}"),
                project.dependencies.create("com.fox2code.FoxLoader:${provider.side.classifier}:${dep}")
            )
            foxLoader.dependencies.addAll(foxLoaderDeps)
            foxLoaderInvoker.dependencies.addAll(foxLoaderDeps)
        } else {
            if (provider.side != EnvType.JOINED) throw IllegalStateException("Cannot use FoxLoader post-2.9 on non-joined side")
            foxLoaderDeps = listOf(project.dependencies.create("com.fox2code.FoxLoader:loader:${dep}"))
        }

        foxLoader.dependencies.addAll(foxLoaderDeps)

        if (foxLoader.dependencies.isEmpty()) {
            throw IllegalStateException("No FoxLoader dependency found!")
        }
    }

    override var commonMod: String = ""
    override var clientMod: String = ""
    override var serverMod: String = ""

    override var modId: String by FinalizeOnRead(MustSet())

    override var modVersion: String = if (project.version.toString() == "unspecified") ""
    else project.version.toString()

    override var modName: String = ""

    override var unofficial: Boolean = false

    @Deprecated("", replaceWith = ReplaceWith("modDescription"))
    override var modDesc: String
        get() = modDescription
        set(value) {
            modDescription = value
        }

    override var modDescription: String = ""

    override var modWebsite: String = ""

    override var preClassTransformer: String = ""

    override var loadingPlugin: String = ""

    private val jarTask = project.tasks.maybeCreate("jar".withSourceSet(provider.sourceSet), Jar::class.java)

    override fun transform(minecraft: MinecraftJar): MinecraftJar {
        val transformed = super.transform(minecraft)

        val name = transformed.path.fileName.toString().substringBeforeLast('.')
        val newPath = Path(transformed.path.toString().replace(name, "$name+FoxLoaderPatched"))

        project.logger.info(transformed.path.absolutePathString())
        project.logger.info(newPath.absolutePathString())

        project.execOps.javaexec {
            it.classpath = foxLoaderInvoker
            it.mainClass.set("com/fox2code/foxloader/invoker/Main")
            it.args(
                transformed.path.absolutePathString(),
                newPath.absolutePathString(),
                "false", isClient.toString()
            )
        }.assertNormalExitValue().rethrowFailure()

        return transformed.copy(path = newPath)
    }

    override fun apply() {
        jarTask.manifest {
            project.logger.lifecycle("Applying attributes")
            it.attributes["For-FoxLoader-Version"] = foxLoader.dependencies.first().version
            it.attributes["For-ReIndev-Version"] = provider.version
            if (modId.isEmpty()) throw IllegalStateException("Mod ID must be set for source set: ${provider.sourceSet.name}")
            it.attributes["ModId"] = this.modId
            if (commonMod.isNotEmpty()) it.attributes["CommonMod"] = this.commonMod
            if (isClient && clientMod.isNotEmpty()) it.attributes["ClientMod"] = this.clientMod
            if (isServer && serverMod.isNotEmpty()) it.attributes["ServerMod"] = this.serverMod
            if (modName.isNotEmpty()) it.attributes["ModName"] = this.modName
            if (modVersion.isNotEmpty()) it.attributes["ModVersion"] = this.modVersion
            if (modDescription.isNotEmpty()) it.attributes["ModDesc"] = this.modDescription
            if (modWebsite.isNotEmpty()) it.attributes["ModWebsite"] = this.modWebsite
            if (preClassTransformer.isNotEmpty()) it.attributes["PreClassTransformer"] = this.preClassTransformer
            if (loadingPlugin.isNotEmpty()) it.attributes["LoadingPlugin"] = this.loadingPlugin
            if (unofficial) it.attributes["Unofficial"] = "true"
        }

        super.apply()
    }

    override fun applyClientRunTransform(config: RunConfig) {
        if (!canCombine) {
            val task = project.tasks.getByName("runClient".withSourceSet(provider.sourceSet))
            task.dependsOn(jarTask)
        } else {
            TODO("FoxLoader 2.0 will hopefully not rely on the jar task")
        }
        super.applyClientRunTransform(config)
        config.mainClass.set("com/fox2code/foxloader/launcher/ClientMain")
        config.jvmArgs(
            "-Dfoxloader.dev-mode=true",
            "-Dfoxloader.inject-mod=${jarTask.outputs.files.singleFile.absolutePath}"
        )
    }

    override fun applyServerRunTransform(config: RunConfig) {
        if (!canCombine) {
            val task = project.tasks.getByName("runServer".withSourceSet(provider.sourceSet))
            task.dependsOn(jarTask)
        } else {
            TODO("FoxLoader 2.0 will hopefully not rely on the jar task")
        }
        super.applyServerRunTransform(config)
        config.mainClass.set("com/fox2code/foxloader/launcher/ServerMain")
        config.jvmArgs(
            "-Dfoxloader.dev-mode=true",
            "-Dfoxloader.inject-mod=${jarTask.outputs.files.singleFile.absolutePath}"
        )
    }

}
