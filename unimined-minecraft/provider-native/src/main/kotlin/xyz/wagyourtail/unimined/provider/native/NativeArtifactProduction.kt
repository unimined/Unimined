package xyz.wagyourtail.unimined.provider.native

import org.gradle.api.GradleException
import xyz.wagyourtail.unimined.minecraft.spi.ArtifactKey
import xyz.wagyourtail.unimined.provider.native.download.Downloader
import xyz.wagyourtail.unimined.provider.native.manifest.ManifestResolver
import xyz.wagyourtail.unimined.provider.native.merge.JarMerger
import xyz.wagyourtail.unimined.provider.native.metadata.MinecraftMetadataAssembler
import xyz.wagyourtail.unimined.provider.native.metadata.PlatformInfo
import xyz.wagyourtail.unimined.provider.native.metadata.ResolvedMinecraftMetadata
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.util.Locale

/**
 * Gradle-free artifact production shared by [NativeProvider] (which wraps
 * these calls in `Provider`s) and [NativeArtifactProducer] (which calls
 * them directly inside artifact-transform execution).
 */
internal object NativeArtifactProduction {

    fun metadata(
        versionId: String,
        resolver: ManifestResolver
    ): ResolvedMinecraftMetadata {
        val version = resolver.versionJson(versionId)
        val root = resolver.versionJsonDocument(versionId)
        return MinecraftMetadataAssembler.assemble(
            version = version,
            root = root,
            platform = PlatformInfo.current()
        )
    }

    fun downloadReference(
        key: ArtifactKey,
        metadata: ResolvedMinecraftMetadata,
        versionId: String,
        downloader: Downloader
    ): Path {
        val ref = metadata.downloads[key]
            ?: throw GradleException(
                "Minecraft $versionId has no ${key.name.lowercase(Locale.ROOT)} jar download"
            )
        return downloader.fetch(
            ref.url,
            "versions/$versionId/${key.name.lowercase(Locale.ROOT)}.jar",
            ref.sha1?.lowercase(Locale.ROOT)
        )
    }

    fun produce(
        versionId: String,
        key: ArtifactKey,
        downloader: Downloader,
        resolver: ManifestResolver,
        rebuild: Boolean
    ): Path {
        return when (key) {
            ArtifactKey.CLIENT ->
                downloadReference(key, metadata(versionId, resolver), versionId, downloader)
            ArtifactKey.SERVER -> unbundleServer(
                downloadReference(key, metadata(versionId, resolver), versionId, downloader),
                versionId, downloader.cacheDir, rebuild
            )
            ArtifactKey.MERGED -> {
                val versionMetadata = metadata(versionId, resolver)
                val clientRef = versionMetadata.downloads[ArtifactKey.CLIENT]
                    ?: throw GradleException("Minecraft $versionId has no client jar download")
                val serverRef = versionMetadata.downloads[ArtifactKey.SERVER]
                    ?: throw GradleException("Minecraft $versionId has no server jar download")
                val client = downloadReference(
                    ArtifactKey.CLIENT, versionMetadata, versionId, downloader
                )
                val server = unbundleServer(
                    downloadReference(
                        ArtifactKey.SERVER, versionMetadata, versionId, downloader
                    ),
                    versionId, downloader.cacheDir, rebuild
                )
                mergePath(
                    downloader.cacheDir, versionId, client, server,
                    clientRef.sha1, serverRef.sha1, rebuild
                )
            }
            else -> throw GradleException("native provider cannot produce artifact key $key")
        }
    }

    /**
     * Modern server downloads are Mojang *bundler* jars — a wrapper that
     * extracts `META-INF/versions/<v>/server-<v>.jar` plus bundled
     * libraries at runtime. For dev we want the real server jar, not the
     * wrapper: leaving it intact poisons merges (bundler classes end up
     * on the dev classpath and loaders like Fabric detect `MC_BUNDLER`
     * by `net/minecraft/bundler/Main.class` presence, unpacking a second
     * obfuscated server jar at launch). Returns the unbundled jar, or
     * [serverJar] unchanged when it isn't a bundler.
     */
    fun unbundleServer(
        serverJar: Path,
        versionId: String,
        cacheDir: Path,
        rebuild: Boolean
    ): Path {
        val entryName = "META-INF/versions/$versionId/server-$versionId.jar"
        val out = cacheDir.resolve("versions/$versionId/server-unbundled.jar")
        java.util.zip.ZipFile(serverJar.toFile()).use { zip ->
            val entry = zip.getEntry(entryName) ?: return serverJar
            if (Files.exists(out) && !rebuild) return out
            Files.createDirectories(out.parent)
            zip.getInputStream(entry).use { input ->
                Files.copy(input, out, StandardCopyOption.REPLACE_EXISTING)
            }
        }
        return out
    }

    fun mergePath(
        cacheDir: Path,
        versionId: String,
        client: Path,
        server: Path,
        clientSha1: String?,
        serverSha1: String?,
        rebuild: Boolean
    ): Path {
        val clientTag = safeHashTag(clientSha1)
        val serverTag = safeHashTag(serverSha1)
        val merged = cacheDir.resolve(
            "versions/$versionId/merged-${JarMerger.FORMAT_VERSION}-$clientTag-$serverTag.jar"
        )
        if (Files.exists(merged) && !rebuild) return merged

        Files.createDirectories(merged.parent)
        val temporary = Files.createTempFile(merged.parent, ".merged-", ".jar")
        try {
            JarMerger.merge(client, server, temporary)
            try {
                Files.move(temporary, merged, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, merged, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
        return merged
    }

    fun emptyAssetIndex(cacheDir: Path): Path {
        val path = cacheDir.resolve("assets/index-empty.json")
        if (!Files.exists(path)) {
            Files.createDirectories(path.parent)
            Files.writeString(path, "{}")
        }
        return path
    }

    private fun safeHashTag(sha1: String?): String =
        sha1?.takeIf { it.matches(Regex("[a-fA-F0-9]{40}")) } ?: "unverified"

}
