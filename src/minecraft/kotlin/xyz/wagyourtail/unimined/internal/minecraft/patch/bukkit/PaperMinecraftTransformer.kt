package xyz.wagyourtail.unimined.internal.minecraft.patch.bukkit

import com.google.gson.JsonParser
import io.sigpipe.jbsdiff.Patch
import org.gradle.api.Project
import xyz.wagyourtail.unimined.api.minecraft.MinecraftJar
import xyz.wagyourtail.unimined.api.minecraft.patch.bukkit.PaperPatcher
import xyz.wagyourtail.unimined.api.unimined
import xyz.wagyourtail.unimined.internal.minecraft.MinecraftProvider
import xyz.wagyourtail.unimined.mapping.EnvType
import xyz.wagyourtail.unimined.util.*
import java.io.File
import java.net.URI
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.zip.ZipException
import kotlin.collections.first
import kotlin.collections.firstOrNull
import kotlin.collections.plus
import kotlin.collections.setOf
import kotlin.io.path.outputStream
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.time.Duration

open class PaperMinecraftTransformer(project: Project,
                                provider: MinecraftProvider
) : CraftbukkitMinecraftTransformer(project, provider, "paper"), PaperPatcher {

    override val supportedEnvs = setOf(EnvType.SERVER)

    override var build: Int by MustSet()

    override var paper: File by FinalizeOnRead(LazyMutable {
        // api.papermc.io v2 was sunset upstream (returns 410) — fill v3
        // serves build metadata and the jar via fill-data.papermc.io
        val metaPath = project.cachingDownload(
            URI.create("https://fill.papermc.io/v3/projects/paper/versions/${provider.version}/builds/$build"),
            cachePath = project.unimined.getGlobalCache().resolve("paper/${provider.version}/build-$build.json"),
            expireTime = Duration.INFINITE
        )
        val download = JsonParser.parseString(metaPath.readText()).asJsonObject
            .getAsJsonObject("downloads")?.getAsJsonObject("server:default")
            ?: throw IllegalStateException("no server:default download in fill v3 response for paper ${provider.version} build $build")
        val name = download.get("name")?.asString
            ?: throw IllegalStateException("no server:default name for paper ${provider.version} build $build")
        val url = download.get("url")?.asString
            ?: throw IllegalStateException("no server:default url for paper ${provider.version} build $build")
        val jar = project.cachingDownload(
            URI.create(url),
            cachePath = project.unimined.getGlobalCache().resolve("paper/${provider.version}/$name"),
            expireTime = Duration.INFINITE
        )
        download.getAsJsonObject("checksums")?.get("sha256")?.asString?.let { sha ->
            check(jar.readBytes().sha256Hex() == sha) {
                "paper dist jar checksum mismatch for $name"
            }
        }
        jar.toFile()
    })

    override var patchName by FinalizeOnRead(Regex("META-INF/.*/server-.*\\.patch"))

    init {
        project.unimined.paperMaven()
        loader = provider.version
    }

    override fun loadLibraries() {
        // TODO
    }

    override fun transform(minecraft: MinecraftJar): MinecraftJar {
        // paper >=1.20.5 ships mojang-mapped servers inside a paperclip
        // bundler; older dists patch into the spigot-obf namespace
        val bundlerEra = provider.minecraftData.mcVersionCompare(provider.version, "1.20.5") >= 0
        val outputNamespace = if (bundlerEra) "mojmap" else "spigotProd"
        val output = MinecraftJar(
            minecraft,
            patches = minecraft.patches + "paper-$loader",
            mappingNamespace = provider.mappings.checkedNsOrNull(outputNamespace)
                ?: throw IllegalStateException(
                    "paper ${provider.version} produces a $outputNamespace-mapped jar, " +
                            "but the $outputNamespace namespace is not in the mappings tree — " +
                            "add `mappings { mojmap() }`"
                )
        )

        val content = paper.toPath().readZipContents()
        // find patch — legacy jars carry paperMC.patch or
        // META-INF/*/server-*.patch; paperclip bundlers carry
        // META-INF/versions/<ver>/*.patch
        val patch = content.firstOrNull {
            it == "paperMC.patch" || it.matches(patchName) || it.matches(bundlerPatchName)
        } ?: throw IllegalStateException(
            "paper dist jar $paper carries no server patch " +
                    "(expected paperMC.patch, $patchName, or $bundlerPatchName)"
        )

        // paperclip records patch input/output sha256 in
        // META-INF/patches.list — verify when present
        val patchesList = content.firstOrNull { it == "META-INF/patches.list" }?.let { name ->
            paper.toPath().readZipInputStreamFor(name) { input ->
                input.bufferedReader().useLines { lines ->
                    lines.map { it.split('\t') }
                        .firstOrNull { it.size >= 7 && it[0] == "versions" }
                        ?.let { it[1] to it[3] }
                }
            }
        }

        // the mojang server jar is itself a bundler in this era — the
        // patch input is the nested server jar, not the outer archive
        val base = nestedServerJar(minecraft.path) ?: minecraft.path.readBytes()
        patchesList?.first?.let { orig ->
            check(base.sha256Hex() == orig) {
                "paper patch input hash mismatch — the jar is not the expected bundled vanilla form"
            }
        }

        output.path.outputStream(StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING).use { os ->
            paper.toPath().readZipInputStreamFor(patch) {
                Patch.patch(base, it.readBytes(), os)
            }
        }
        patchesList?.second?.let { out ->
            check(output.path.readBytes().sha256Hex() == out) {
                "patched jar does not match patches.list output hash"
            }
        }

        addExtraInnerClassMappings(minecraft, output)

        return output
    }

    private val bundlerPatchName = Regex("META-INF/versions/[^/]+/[^/]+\\.patch")

    private fun ByteArray.sha256Hex() =
        MessageDigest.getInstance("SHA-256").digest(this).toHex()

    private fun nestedServerJar(jar: Path): ByteArray? = try {
        jar.readZipContents().firstOrNull {
            it.matches(Regex("META-INF/versions/[^/]+/server-[^/]+\\.jar"))
        }?.let { entry ->
            jar.readZipInputStreamFor(entry) { it.readBytes() }
        }
    } catch (e: ZipException) {
        null
    }

}
