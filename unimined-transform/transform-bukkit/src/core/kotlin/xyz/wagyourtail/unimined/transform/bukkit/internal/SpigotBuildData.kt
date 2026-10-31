package xyz.wagyourtail.unimined.transform.bukkit.internal

import xyz.wagyourtail.unimined.provider.native.download.Downloader
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.io.path.readText

/**
 * Resolves spigot's versioned-NMS package token (`v1_20_R3`-style)
 * from BuildData — the same source 1.x used: the `refs.craftbukkit`
 * commit in `hub.spigotmc.org/versions/<rev>.json`, then
 * `<minecraft_version>` out of that commit's CraftBukkit pom.
 *
 * Pre-1.17 versions have no versioned package — [supportsVersionedNms]
 * gates the relocation on the mc version so the token fetch only
 * happens where it applies.
 */
internal class SpigotBuildData(private val downloader: Downloader) {

    private val cbRefs = Regex(""""craftbukkit"\s*:\s*"([0-9a-f]+)"""")

    /** Whether [mcVersion] uses versioned NMS packages (>=1.17). */
    fun supportsVersionedNms(mcVersion: String): Boolean {
        val (major, minor) = mcVersion.split('.')
            .mapNotNull { it.filter(Char::isDigit).toIntOrNull() }
            .let { it.getOrElse(1) { 0 } to it.getOrElse(0) { 0 } }
        return minor > 1 || (minor == 1 && major >= 17)
    }

    /**
     * The `v<maj>_<min>_R<rev>` package token for [mcVersion].
     * Fails loudly when BuildData can't be resolved — the relocation
     * is required for correctness on >=1.17, not optional.
     */
    fun versionPackage(mcVersion: String): String {
        val info = downloader.fetch(
            "https://hub.spigotmc.org/versions/$mcVersion.json",
            "builddata/$mcVersion.json"
        )
        val ref = cbRefs.find(info.readText())?.groupValues?.get(1)
            ?: error("no craftbukkit ref in $info")
        val pom = downloader.fetch(
            "https://hub.spigotmc.org/stash/projects/SPIGOT/repos/" +
                "craftbukkit/raw/pom.xml?at=$ref",
            "builddata/craftbukkit-pom-$ref.xml"
        )
        val doc = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder().parse(pom.toFile())
        return doc.getElementsByTagName("minecraft_version")
            .item(0)?.textContent
            ?: error("no minecraft_version in $pom")
    }
}
