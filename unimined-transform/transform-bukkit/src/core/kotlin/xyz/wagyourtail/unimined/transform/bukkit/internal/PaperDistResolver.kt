package xyz.wagyourtail.unimined.transform.bukkit.internal

import xyz.wagyourtail.unimined.provider.native.download.Downloader
import java.nio.file.Path

/**
 * Resolves Paper distribution jars through the v2 download API —
 * `api.papermc.io` serves the build-keyed server jar whose embedded
 * binary patch (`paperMC.patch` / `META-INF/…/server-*.patch`) the
 * [PaperPatchProcessor] applies to the vanilla server jar.
 *
 * Pre-bundler era only: Paper >=1.20.4 ships a bundler jar
 * (`versions/` + `libraries/` layout, no embedded patch) which takes
 * a different extraction path — tracked as slice 3. Callers gate on
 * the descriptor's `serverArtifact` capability.
 */
internal object PaperDistResolver {

    /** Download the v2 dist jar for ([mcVersion], [build]) into the cache. */
    fun serverJar(downloader: Downloader, mcVersion: String, build: String): Path =
        downloader.fetch(
            "https://api.papermc.io/v2/projects/paper/versions/$mcVersion" +
                "/builds/$build/downloads/paper-$mcVersion-$build.jar",
            "paper/$mcVersion/paper-$mcVersion-$build.jar"
        )
}
