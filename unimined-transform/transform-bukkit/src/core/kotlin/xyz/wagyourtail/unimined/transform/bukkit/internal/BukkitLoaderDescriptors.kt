package xyz.wagyourtail.unimined.transform.bukkit.internal

import xyz.wagyourtail.unimined.transform.loader.AccessDiscovery
import xyz.wagyourtail.unimined.transform.loader.LoaderDescriptor
import xyz.wagyourtail.unimined.transform.loader.LoaderDescriptorSource
import xyz.wagyourtail.unimined.transform.loader.LoaderRepo
import xyz.wagyourtail.unimined.transform.loader.LoaderMetadataSpec

/**
 * Bukkit-family loader products — the 1.x subclasses
 * (`Craftbukkit`/`Spigot`/`Paper`) expressed as data rows. What
 * actually varies per product is *how the server artifact is
 * produced*, which the wiring reads from [capabilities]:
 *
 *  - `craftbukkit`/`spigot` — no public dist endpoint; the server jar
 *    must come from a non-native `provider-*` module (BuildTools,
 *    mavenizer, …). `serverArtifact` marks that.
 *  - `paper` — public dist endpoints: `api.papermc.io` v2 (legacy +
 *    archived builds) and `fill.papermc.io` v3 (current). Older dist
 *    jars embed a binary `server-*.patch` to jbsdiff onto the vanilla
 *    server; bundler-era jars (≈1.20.4+) ship `versions/` +
 *    `libraries/` for extraction instead. `serverArtifact` = the
 *    patch/extraction step; `apiArtifact` = the optional api-only
 *    compile mode.
 */
internal class BukkitLoaderDescriptors : LoaderDescriptorSource {

    private val spigotNexus = LoaderRepo.Maven(
        "https://hub.spigotmc.org/nexus/content/groups/public/")
    private val sonatypeSnapshots = LoaderRepo.Maven(
        "https://oss.sonatype.org/content/repositories/snapshots/")

private val wagYourMaven = listOf(
    LoaderRepo.Maven("https://maven.wagyourtail.xyz/releases"),
    LoaderRepo.Maven("https://maven.wagyourtail.xyz/snapshots")
)

    override fun descriptors(): List<LoaderDescriptor> = listOf(
        LoaderDescriptor(
            id = "craftbukkit",
            family = "bukkit",
            transformPlugin = "xyz.wagyourtail.unimined.transform.bukkit",
            repos = listOf(
                spigotNexus,
                sonatypeSnapshots,
                LoaderRepo.Maven("https://maven.minecraftforge.net/")
            ) + wagYourMaven,
            // server jar comes from a non-native provider; the maven
            // coordinate is the api/implementation surface users can
            // additionally compile against
            loaderCoords = { _, v -> "org.bukkit:craftbukkit:$v" },
            prodNamespace = "spigotProd",
            capabilities = mapOf(
                "serverArtifact" to "provider",
                "apiArtifact" to "org.bukkit:craftbukkit"
            ),
            metadata = LoaderMetadataSpec(
                fileName = "plugin.yml",
                accessDiscovery = emptySet()
            )
        ),
        LoaderDescriptor(
            id = "spigot",
            family = "bukkit",
            transformPlugin = "xyz.wagyourtail.unimined.transform.bukkit",
            repos = listOf(spigotNexus, sonatypeSnapshots) + wagYourMaven,
            loaderCoords = { _, v -> "org.spigotmc:spigot:$v" },
            prodNamespace = "spigotProd",
            capabilities = mapOf(
                "serverArtifact" to "provider",
                "apiArtifact" to "org.spigotmc:spigot-api"
            ),
            metadata = LoaderMetadataSpec(
                fileName = "plugin.yml",
                accessDiscovery = emptySet()
            )
        ),
        LoaderDescriptor(
            id = "paper",
            family = "bukkit",
            transformPlugin = "xyz.wagyourtail.unimined.transform.bukkit",
            repos = listOf(
                LoaderRepo.Maven("https://repo.papermc.io/repository/maven-public/")
            ) + wagYourMaven,
            // api-side maven surface — paper-api is published; the
            // server jar resolves through the dist endpoint, not maven
            loaderCoords = { _, v -> "io.papermc.paper:paper-api:$v" },
            prodNamespace = "spigotProd",
            capabilities = mapOf(
                "serverArtifact" to "distPatch",
                "apiArtifact" to "io.papermc.paper:paper-api",
                "distEndpoint" to "https://fill.papermc.io/v3",
                "distEndpointLegacy" to "https://api.papermc.io/v2"
            ),
            metadata = LoaderMetadataSpec(
                fileName = "paper-plugin.yml",
                accessDiscovery = emptySet()
            )
        ),
    )

}
