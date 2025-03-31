package xyz.wagyourtail.unimined.internal.minecraft.patch.bukkit.buildtools

import com.google.gson.JsonParser
import org.eclipse.jgit.api.Git
import org.gradle.api.Project
import xyz.wagyourtail.unimined.api.unimined
import xyz.wagyourtail.unimined.internal.minecraft.MinecraftProvider
import xyz.wagyourtail.unimined.util.cachingDownload
import java.net.URI
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.io.path.exists
import kotlin.io.path.readText

class BuildToolsExecutor(
    private val project: Project,
    private val provider: MinecraftProvider,
    private val rev: String,
    private val cache: Path,
    private val target: BuildTarget
) {

    val buildInfo: BuildInfo by lazy {
        val buildInfoFile = cache.resolve("BuildInfo-$rev.json")
        if (!buildInfoFile.exists() || project.unimined.forceReload) {
            project.cachingDownload(
                URI.create("https://hub.spigotmc.org/versions/${rev}.json"),
                cachePath = buildInfoFile
            )
        }
        val buildInfoJson = JsonParser.parseString(buildInfoFile.readText())
        parseBuildInfo(buildInfoJson.asJsonObject)
    }

    val buildTools by lazy {
        val buildTools = cache.resolve("BuildTools.jar")
        if (!buildTools.exists() || project.unimined.forceReload) {
            project.cachingDownload(
                URI.create("https://hub.spigotmc.org/jenkins/job/BuildTools/lastSuccessfulBuild/artifact/target/BuildTools.jar"),
                cachePath = buildTools
            )
        }
        buildTools
    }

    val craftbukkit by lazy {
        cache.resolve("CraftBukkit").also {
            cloneRepo("craftbukkit", buildInfo.refs.craftbukkit, it)
        }
    }

    val bukkit by lazy {
        cache.resolve("Bukkit").also {
            cloneRepo("bukkit", buildInfo.refs.bukkit, it)
        }
    }

    val spigot by lazy {
        cache.resolve("Spigot").also {
            cloneRepo("spigot", buildInfo.refs.spigot, it)
        }
    }

    val buildData by lazy {
        cache.resolve("BuildData").also {
            cloneRepo("builddata", buildInfo.refs.buildData, it)
        }
    }

    fun cloneRepo(name: String, hash: String, dir: Path) {
        if (!dir.exists() || project.unimined.forceReload) {
            Git.cloneRepository().setDirectory(dir.toFile()).setURI("https://hub.spigotmc.org/stash/scm/spigot/${name}.git").call()
        }
        Git.open(dir.toFile()).use { git ->
            git.checkout().setName(hash).call()
        }
    }

    fun cloneRepos() {
        project.logger.info("[Unimined/BuildTools] craftbukkit: $craftbukkit")
        project.logger.info("[Unimined/BuildTools] bukkit: $bukkit")
        project.logger.info("[Unimined/BuildTools] spigot: $spigot")
        project.logger.info("[Unimined/BuildTools] buildData: $buildData")
    }

    val versionInfo by lazy {
        val data = JsonParser.parseString(buildData.resolve("info.json").readText())
        parseVersionInfo(data.asJsonObject)
    }

    val targetDir by lazy {
        when (target) {
            BuildTarget.SPIGOT -> spigot.resolve("Spigot-Server")
            BuildTarget.CRAFTBUKKIT -> craftbukkit
        }.resolve("target")
    }

    val cBpom by lazy {
        val dbF = DocumentBuilderFactory.newInstance()
        dbF.isValidating = false
        dbF.isNamespaceAware = false
        dbF.isIgnoringComments = true
        dbF.isIgnoringElementContentWhitespace = true
        val db = dbF.newDocumentBuilder()
        db.parse(craftbukkit.resolve("pom.xml").toFile())
    }

    val spigotPom by lazy {
        val repo = Git.open(spigot.toFile())
        repo.submoduleInit().call()
        val dbF = DocumentBuilderFactory.newInstance()
        dbF.isValidating = false
        dbF.isNamespaceAware = false
        dbF.isIgnoringComments = true
        dbF.isIgnoringElementContentWhitespace = true
        val db = dbF.newDocumentBuilder()
        db.parse(spigot.resolve("Spigot-Server").resolve("pom.xml").toFile())
    }

    val targetPom by lazy {
        when (target) {
            BuildTarget.SPIGOT -> {
                runBuildTools()
                spigotPom
            }
            BuildTarget.CRAFTBUKKIT -> cBpom
        }
    }

    val version by lazy {
        cBpom.getElementsByTagName("version").item(0).textContent
    }

    val minecraftVersion by lazy {
        cBpom.getElementsByTagName("minecraft_version").item(0).textContent
    }

    fun runBuildTools(): Path {
        cloneRepos()
        if (versionInfo.minecraftVersion != provider.version) {
            throw IllegalStateException("version ${versionInfo.minecraftVersion} in build data does not match requested minecraft version ${provider.version}")
        }

        val targetFile = targetDir.resolve("${target.name.lowercase()}-${version}.jar")

        if (!targetFile.exists() || project.unimined.forceReload) {
            project.logger.lifecycle("[Unimined/BuildTools] running build tools")
            project.javaexec {
                it.classpath(project.files(buildTools))
                it.mainClass.set("org.spigotmc.builder.Bootstrap")
                it.args("--compile", target.name.lowercase(), "--dont-update", "--dev")
                it.workingDir = cache.toFile()
            }.rethrowFailure().assertNormalExitValue()
        }

        return targetFile
    }

    enum class BuildTarget {
        SPIGOT,
        CRAFTBUKKIT
    }

}