package xyz.wagyourtail.unimined.internal.minecraft.patch.conversion.reindev

import org.gradle.api.Project
import xyz.wagyourtail.unimined.api.minecraft.MinecraftJar
import xyz.wagyourtail.unimined.api.unimined
import xyz.wagyourtail.unimined.internal.minecraft.MinecraftProvider
import xyz.wagyourtail.unimined.internal.minecraft.resolver.MinecraftDownloader
import xyz.wagyourtail.unimined.mapping.EnvType
import xyz.wagyourtail.unimined.mapping.Namespace
import xyz.wagyourtail.unimined.util.FinalizeOnRead
import xyz.wagyourtail.unimined.util.LazyMutable
import xyz.wagyourtail.unimined.util.cachingDownload
import java.net.URI
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.time.Duration

class ReIndevDownloader(project: Project, override val provider: ReIndevProvider) : MinecraftDownloader(project, provider) {

    val fileBaseURL: URI = URI.create("https://cdn.fox2code.com/files/")

    override val mcVersionFolder: Path by lazy {
        project.unimined.getGlobalCache()
            .resolve("net")
            .resolve("silveros")
            .resolve("reindev")
            .resolve(version)
    }

    override var metadataURL: URI by FinalizeOnRead(LazyMutable {
        val versionIndex = getVersionFromLauncherMeta("b1.7.3")
        val url = versionIndex.get("url").asString
        URI.create(url)
    })

    override val minecraftClient: MinecraftJar by lazy {
        project.logger.info("[Unimined/MinecraftDownloader] retrieving ReIndev client jar")
        val clientPath = mcVersionFolder.resolve("reindev-$version-client.jar")
        if (!clientPath.exists() || project.unimined.forceReload) {
            mcVersionFolder.createDirectories()
            project.cachingDownload(
                fileBaseURL.resolve("reindev_${version}.jar"),
                cachePath = clientPath,
                expireTime = Duration.INFINITE
            )
        }
        MinecraftJar(
            mcVersionFolder,
            "reindev",
            EnvType.CLIENT,
            version,
            listOf(),
            Namespace("official"),
            null,
            "jar",
            clientPath
        )
    }

    override val minecraftServer: MinecraftJar by lazy {
        project.logger.info("[Unimined/MinecraftDownloader] retrieving ReIndev server jar")
        val serverPath = mcVersionFolder.resolve("reindev-${version}-server.jar")
        if (!serverPath.exists() || project.unimined.forceReload) {
            mcVersionFolder.createDirectories()
            project.cachingDownload(
                fileBaseURL.resolve("reindev_${version}_server.jar"),
                cachePath = serverPath,
                expireTime = Duration.INFINITE
            )
        }
        MinecraftJar(
            mcVersionFolder,
            "reindev",
            EnvType.SERVER,
            version,
            listOf(),
            Namespace("official"),
            null,
            "jar",
            serverPath
        )
    }
}
