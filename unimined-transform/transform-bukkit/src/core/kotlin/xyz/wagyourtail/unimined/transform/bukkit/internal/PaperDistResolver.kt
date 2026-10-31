package xyz.wagyourtail.unimined.transform.bukkit.internal

import xyz.wagyourtail.unimined.provider.native.download.Downloader
import java.nio.file.Path
import kotlin.io.path.readText

/**
 * Resolves Paper distribution jars through the fill v3 API —
 * `api.papermc.io/v2` was sunset upstream, so build metadata and the
 * jar both come from `fill.papermc.io`. The downloaded jar carries
 * the embedded binary patch (`paperMC.patch`,
 * `META-INF/.../server-(ver).patch`, or the bundler-era
 * `META-INF/versions/.../(name).patch`) that [PaperPatchProcessor]
 * applies to the vanilla server jar.
 */
internal object PaperDistResolver {

    /**
     * Download the dist jar for ([mcVersion], [build]) — [build] may
     * be a build number or `"latest"` — into the cache.
     */
    fun serverJar(downloader: Downloader, mcVersion: String, build: String): Path {
        val meta = downloader.fetch(
            "https://fill.papermc.io/v3/projects/paper/versions/" +
                "$mcVersion/builds/$build",
            "paper/$mcVersion/build-$build.json"
        ).readText()
        // the downloads object nests checksums:{} inside the entry —
        // non-greedy fields anchored at the server:default key so the
        // block boundary doesn't have to be parsed
        fun entry(key: String): String? =
            Regex(""""server:default"\s*:\s*\{.*?"$key"\s*:\s*"([^"]+)"""",
                RegexOption.DOT_MATCHES_ALL)
                .find(meta)?.groupValues?.get(1)
        val url = entry("url")
            ?: error("no server:default url in fill v3 response for " +
                    "paper $mcVersion build $build")
        val name = entry("name")
            ?: error("no server:default name for paper $mcVersion $build")
        val sha = Regex(""""server:default".*?"sha256"\s*:\s*"([0-9a-f]+)"""",
            RegexOption.DOT_MATCHES_ALL).find(meta)?.groupValues?.get(1)
        return downloader.fetch(url, "paper/$mcVersion/$name", sha)
    }
}
