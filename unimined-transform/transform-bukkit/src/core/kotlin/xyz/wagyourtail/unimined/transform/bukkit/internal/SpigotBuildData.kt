package xyz.wagyourtail.unimined.transform.bukkit.internal

import xyz.wagyourtail.unimined.minecraft.download.Downloader
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.io.path.readText

/**
 * Resolves spigot's versioned-NMS package token (`v1_20_R3`-style)
 * from BuildData — the same source 1.x used: the `refs.craftbukkit`
 * commit in `hub.spigotmc.org/versions/<rev>.json`, then
 * `<minecraft_version>` out of that commit's CraftBukkit pom.
 *
 * CraftBukkit has used versioned NMS packages since ~1.4.x
 * (`v1_8_R3`-style), so the relocation applies to every
 * BuildTools-era version; the pom token is authoritative.
 */
internal class SpigotBuildData(private val downloader: Downloader) {

    // versions.json keys are the capitalized git repo names
    // ("CraftBukkit", "Spigot", ...) — match case-insensitively
    private val cbRefs = Regex(
        """"craftbukkit"\s*:\s*"([0-9a-f]+)"""",
        RegexOption.IGNORE_CASE
    )

    /**
     * The `v<maj>_<min>_R<rev>` package token for [mcVersion].
     * Fails loudly when BuildData can't be resolved — the relocation
     * is required for correctness, not optional.
     */
    fun versionPackage(mcVersion: String): String {
        val info = downloader.fetch(
            "https://hub.spigotmc.org/versions/$mcVersion.json",
            "builddata/$mcVersion.json"
        )
        val ref = cbRefs.find(info.readText())?.groupValues?.get(1)
            ?: error("no craftbukkit ref in $info")
        val pom = downloader.fetch(
            // the pretty URL (/stash/projects/...) sits behind a
            // Cloudflare browser challenge; the Bitbucket REST path
            // serves the same raw file to non-browser clients
            "https://hub.spigotmc.org/stash/rest/api/latest/projects/" +
                "SPIGOT/repos/craftbukkit/raw/pom.xml?at=$ref",
            "builddata/craftbukkit-pom-$ref.xml"
        )
        val doc = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder().parse(pom.toFile())
        return doc.getElementsByTagName("minecraft_version")
            .item(0)?.textContent
            ?: error("no minecraft_version in $pom")
    }
}
