package xyz.wagyourtail.unimined.provider.native

import org.gradle.api.GradleException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import xyz.wagyourtail.unimined.minecraft.spi.ArtifactKey
import xyz.wagyourtail.unimined.provider.native.download.Downloader
import xyz.wagyourtail.unimined.provider.native.manifest.ManifestResolver
import xyz.wagyourtail.unimined.provider.native.merge.JarMerger
import xyz.wagyourtail.unimined.provider.native.metadata.MinecraftMetadataAssembler
import xyz.wagyourtail.unimined.provider.native.metadata.PlatformInfo
import xyz.wagyourtail.unimined.provider.native.metadata.ResolvedMinecraftMetadata
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import xyz.wagyourtail.unimined.provider.native.internal.io.AtomicIo
import java.nio.file.Path
import java.security.MessageDigest
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.*

/**
 * Gradle-free artifact production shared by [NativeProvider] (which wraps
 * these calls in `Provider`s) and [NativeArtifactProducer] (which calls
 * them directly inside artifact-transform execution).
 */
internal object NativeArtifactProduction {

    fun metadata(
        versionId: String,
        resolver: ManifestResolver,
        obfuscatedPrefixes: List<String> = emptyList()
    ): ResolvedMinecraftMetadata {
        val version = resolver.versionJson(versionId)
        val root = resolver.versionJsonDocument(versionId)
        return MinecraftMetadataAssembler.assemble(
            version = version,
            root = root,
            platform = PlatformInfo.current(),
            obfuscatedPrefixes = obfuscatedPrefixes
        )
    }

    fun downloadReference(
        key: ArtifactKey,
        metadata: ResolvedMinecraftMetadata,
        versionId: String,
        downloader: Downloader
    ): Path {
        val ref = metadata.downloads[key]
        if (ref == null) {
            if (key != ArtifactKey.SERVER) throw GradleException(
                "Minecraft $versionId has no ${key.name.lowercase(Locale.ROOT)} jar download"
            )
            // pre-1.6 metadata carries no server download — fall back to
            // the betacraft archive (1.x parity)
            return downloader.fetch(
                betacraftServerUrl(versionId, downloader),
                "versions/$versionId/server.jar",
                null
            )
        }
        return downloader.fetch(
            ref.url,
            "versions/$versionId/${key.name.lowercase(Locale.ROOT)}.jar",
            ref.sha1?.lowercase(Locale.ROOT)
        )
    }

    private const val MC_C2S_URL =
        "https://maven.wagyourtail.xyz/releases/mc-c2s.json"

    private const val BETACRAFT_SERVER_ARCHIVE =
        "https://files.betacraft.uk/server-archive"

    /**
     * Beta/alpha/early-release server jar URL on the betacraft archive.
     * Client and server version ids diverge for old versions, so the id
     * is first mapped through the `mc-c2s` override list (1.x semantics).
     */
    private fun betacraftServerUrl(versionId: String, downloader: Downloader): String {
        val overrides = try {
            val f = downloader.fetchMetadata(
                MC_C2S_URL, "metadata/mc-c2s.json", java.time.Duration.ofDays(7)
            )
            kotlinx.serialization.json.Json {
                ignoreUnknownKeys = true
            }.parseToJsonElement(
                Files.readString(f)
            ).jsonObject.mapValues { it.value.jsonPrimitive.content }
        } catch (t: Throwable) {
            emptyMap()
        }
        val serverId = overrides[versionId] ?: versionId
        val uriPart = when {
            versionId.startsWith("b") -> "beta/$serverId"
            versionId.startsWith("a") -> "alpha/$serverId"
            else -> {
                val folder = versionId.split(".").subList(0, 2).joinToString(".")
                "release/$folder/$serverId"
            }
        }
        return "$BETACRAFT_SERVER_ARCHIVE/$uriPart.jar"
    }

    fun produce(
        versionId: String,
        key: ArtifactKey,
        downloader: Downloader,
        resolver: ManifestResolver,
        rebuild: Boolean,
        mergeOptions: JarMerger.Options = JarMerger.Options(),
        preMerge: ((client: Path, server: Path) -> Pair<Path, Path>)? = null
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
                // pre-1.6 metadata carries no server download —
                // downloadReference falls back to the betacraft
                // archive; only the sha1 (for merge caching) is absent
                val serverRef = versionMetadata.downloads[ArtifactKey.SERVER]
                var client = downloadReference(
                    ArtifactKey.CLIENT, versionMetadata, versionId, downloader
                )
                var server = unbundleServer(
                    downloadReference(
                        ArtifactKey.SERVER, versionMetadata, versionId, downloader
                    ),
                    versionId, downloader.cacheDir, rebuild
                )
                // split-era versions (no manifest server download) have
                // disjoint client/server obf namespaces — the caller's
                // hook remaps both sides into a shared namespace before
                // the member-level merge
                var clientTag = clientRef.sha1
                var serverTag = serverRef?.sha1
                preMerge?.let { hook ->
                    if (serverRef == null) {
                        val (c, s) = hook(client, server)
                        client = c; server = s
                        // hook outputs replace the merge inputs — key
                        // the cache on their content, not the raw jars
                        clientTag = contentSha1(client)
                        serverTag = contentSha1(server)
                    }
                }
                mergePath(
                    downloader.cacheDir, versionId, client, server,
                    clientTag, serverTag, rebuild, mergeOptions
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
            val tmp = AtomicIo.tempSibling(out)
            try {
                zip.getInputStream(entry).use { input ->
                    Files.copy(input, tmp, StandardCopyOption.REPLACE_EXISTING)
                }
                AtomicIo.moveAtomic(tmp, out)
            } finally {
                Files.deleteIfExists(tmp)
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
        rebuild: Boolean,
        options: JarMerger.Options = JarMerger.Options()
    ): Path {
        val clientTag = safeHashTag(clientSha1)
        val serverTag = safeHashTag(serverSha1)
        // the merge options affect the output, so the side-cache key
        // folds them in — library-set content (path+size+mtime per
        // jar), globs, and the strip flag
        val optionsTag = safeHashTag(
            listOf(
                options.stripBundledServerLibraries,
                options.includeGlobs, options.excludeGlobs,
                options.libraryJars.map {
                    val attrs = Files.readAttributes(
                        it, BasicFileAttributes::class.java)
                    "${it.fileName}:${attrs.size()}:${attrs.lastModifiedTime()}"
                }
            ).hashCode().toString()
        )
        val merged = cacheDir.resolve(
            "versions/$versionId/merged-${JarMerger.FORMAT_VERSION}-$clientTag-$serverTag-$optionsTag.jar"
        )
        if (Files.exists(merged) && !rebuild) return merged

        Files.createDirectories(merged.parent)
        val temporary = Files.createTempFile(merged.parent, ".merged-", ".jar")
        try {
            JarMerger.merge(client, server, temporary, options)
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
            AtomicIo.tempSibling(path).also {
                Files.writeString(it, "{}")
                AtomicIo.moveAtomic(it, path)
            }
        }
        return path
    }

    private fun safeHashTag(sha1: String?): String =
        sha1?.takeIf { it.matches(Regex("[a-fA-F0-9]{40}")) } ?: "unverified"

    private fun contentSha1(path: Path): String = Files.newInputStream(path).use { input ->
        MessageDigest.getInstance("SHA-1").run {
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                update(buffer, 0, count)
            }
            digest().joinToString("") { "%02x".format(it) }
        }
    }

}
