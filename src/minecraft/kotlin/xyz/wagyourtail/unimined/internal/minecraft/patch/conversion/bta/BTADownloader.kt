package xyz.wagyourtail.unimined.internal.minecraft.patch.conversion.bta

import org.gradle.api.Project
import xyz.wagyourtail.unimined.api.minecraft.MinecraftJar
import xyz.wagyourtail.unimined.api.unimined
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

class BTADownloader(project: Project, override val provider: BTAProvider) : MinecraftDownloader(project, provider) {
	private val channel: String
		get() = this.provider.channel

	private val baseURL = URI.create("https://downloads.betterthanadventure.net/")

	private val clientBaseURL: URI by lazy {
		baseURL.resolve("bta-client/$channel/v$version/")
	}

	private val serverBaseURL: URI by lazy {
		baseURL.resolve("bta-server/$channel/v$version/")
	}

	override val mcVersionFolder: Path by lazy {
		project.unimined.getGlobalCache()
			.resolve("net")
			.resolve("betterthanadventure")
			.resolve("bta")
			.resolve(version)
	}

	override var metadataURL: URI by FinalizeOnRead(LazyMutable {
		clientBaseURL.resolve("manifest.json")
	})

	override val minecraftClient: MinecraftJar by lazy {
		project.logger.info("[Unimined/MinecraftDownloader] retrieving BTA client jar")
		val clientPath = mcVersionFolder.resolve("bta-$version-client.jar")
		if (!clientPath.exists() || project.unimined.forceReload) {
			mcVersionFolder.createDirectories()
			project.cachingDownload(
				clientBaseURL.resolve("client.jar"),
				cachePath = clientPath,
				expireTime = Duration.INFINITE
			)
		}
		MinecraftJar(
			mcVersionFolder,
			"bta",
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
		project.logger.info("[Unimined/MinecraftDownloader] retrieving BTA server jar")
		val serverPath = mcVersionFolder.resolve("bta-$version-server.jar")
		if (!serverPath.exists() || project.unimined.forceReload) {
			mcVersionFolder.createDirectories()
			project.cachingDownload(
				serverBaseURL.resolve("server.jar"),
				cachePath = serverPath,
				expireTime = Duration.INFINITE
			)
		}
		MinecraftJar(
			mcVersionFolder,
			"bta",
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
