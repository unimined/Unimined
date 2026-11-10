package xyz.wagyourtail.unimined.transform.jarmod.internal

import xyz.wagyourtail.unimined.transform.loader.LoaderDescriptor
import xyz.wagyourtail.unimined.transform.loader.LoaderDescriptorSource
import xyz.wagyourtail.unimined.transform.loader.LoaderRepo
import xyz.wagyourtail.unimined.transform.loader.LoaderMetadataSpec

/**
 * Jarmod-family loader products — `jarmod` (vanilla jar injection +
 * JarModAgent runtime coremodding). The "loader" coordinate is the
 * JarModAgent artifact itself. ModLoader isn't a loader — it's a
 * `jarMod` dep patched by `jarmod-modloader-fixes` (exists-gated
 * on `ModLoader.class`); `fg1` is FML-era forge on the same family.
 */
internal class JarModLoaderDescriptors : LoaderDescriptorSource {

    override fun descriptors(): List<LoaderDescriptor> = listOf(
        LoaderDescriptor(
            id = "jarmod",
            family = "jarmod",
            repos = listOf(
                // wagyourtail releases/snapshots mirror PROXIES forge
                // maven — it serves de.oceanlabs POMs but its artifact
                // set lags (26.3-era mcp_config zips 404 while the POM
                // resolves). de.oceanlabs must stay OUT of the filter
                // so the real forge maven wins — it's ensured by the
                // searge/mcp paths and fg1's own repos anyway.
                LoaderRepo.Maven("https://maven.wagyourtail.xyz/releases/", includeGroupRegex = "xyz\\.wagyourtail(\\..*)?"),
                LoaderRepo.Maven("https://maven.wagyourtail.xyz/snapshots/", includeGroupRegex = "xyz\\.wagyourtail(\\..*)?")
            ),
            loaderCoords = { _, v -> "xyz.wagyourtail.unimined:jarmod-agent:$v:all" },
            // baseline loader — jars into any product
            supportedProducts = setOf("*"),
            prodNamespace = "official",
            transformPlugin = "xyz.wagyourtail.unimined.transform.jarmod"
        ),
        LoaderDescriptor(
            id = "fg1",
            family = "jarmod",
            repos = listOf(
                // same lagged-mirror scoping as jarmod above — keep
                // de.oceanlabs off the wagyourtail proxy
                // `de.oceanlabs.mcp:mcp_legacy` (FG1-era MCP zips) is
                // hosted HERE — files.minecraftforge.net 301s into a
                // maven.minecraftforge.net 404 for it. Scoped by
                // module, not group: the same group also carries
                // `mcp_config` (26.x userdev) which wagyourtail only
                // POM-mirrors — a group filter would shadow the real
                // forge maven on artifact fetch.
                LoaderRepo.Maven(
                    "https://maven.wagyourtail.xyz/releases/",
                    includeGroupRegex = "xyz\\.wagyourtail(\\..*)?",
                    includeModules = setOf("de.oceanlabs.mcp" to "mcp_legacy")),
                LoaderRepo.Maven("https://maven.wagyourtail.xyz/snapshots/", includeGroupRegex = "xyz\\.wagyourtail(\\..*)?"),
                // FG1-era forge zips — the user-facing "loader version"
                // is forge's (1.x `forge { loader(coord) }` parity); the
                // era artifact merges into the dev jar via the `forge`
                // config (a user-declared dep — the CLI emits it)
                LoaderRepo.Maven("https://files.minecraftforge.net/maven/", includeGroupRegex = "net\\.minecraftforge(\\..*)?")
            ),
            // FG1-era artifacts all live under `net.minecraftforge:forge`
            // — 1.3+ ships `:universal`, pre-1.3 ships per-side
            // `:client`/`:server` (the CLI emits the side classifier)
            loaderCoords = { mc, v -> "net.minecraftforge:forge:$mc-$v:universal@zip" },
            prodNamespace = "official",
            transformPlugin = "xyz.wagyourtail.unimined.transform.jarmod"
        ),
        // 1.x LiteLoaderMinecraftTransformer extended the jarmod
        // transformer — jarmod-family wiring with its own artifact.
        // dl.liteloader.com is an artifact-only repo; sponge for its
        // mixin/lib deps.
        LoaderDescriptor(
            id = "liteloader",
            family = "jarmod",
            repos = listOf(
                // repo.mumfrey.com is the real Nexus behind
                // dl.liteloader.com's feeds — declared first so
                // artifacts resolve directly, avoiding the
                // https->http downgrade redirect Gradle refuses
                LoaderRepo.Maven(
                    "https://repo.mumfrey.com/content/repositories/snapshots/"
                ),
                LoaderRepo.Maven(
                    "https://repo.mumfrey.com/content/repositories/liteloader/"
                ),
                // NOTE: dl.liteloader.com/versions is intentionally
                // absent — its artifact hits 302 to an insecure http
                // redirector, which Gradle treats as a hard failure
                // that short-circuits resolution before later repos
                // get tried
                LoaderRepo.Maven("https://repo.spongepowered.org/maven/"),
                // release-era artifacts (e.g. 1.7.10_04) live here under a
                // maven layout despite versions.json's "ivy" label — last
                // maven so mumfrey wins for snapshots; no poms exist on
                // this host so it must be artifact-only metadata
                LoaderRepo.Maven(
                    "https://dl.liteloader.com/versions/",
                    metadata = LoaderRepo.MetadataMode.ARTIFACT_ONLY
                ),
                // legacy redist is a plain file feed — ivy pattern
                LoaderRepo.Ivy(
                    "https://dl.liteloader.com/redist/legacy/",
                    "[artifact]_[revision].[ext]",
                    includeModules = setOf("com.mumfrey" to "liteloader")
                )
            ),
            loaderCoords = { _, v -> "com.mumfrey:liteloader:$v" },
            prodNamespace = "official",
            wiringVariant = "liteloader",
            transformPlugin = "xyz.wagyourtail.unimined.transform.jarmod"
        ),
        LoaderDescriptor(
            id = "rift",
            family = "jarmod",
            repos = listOf(
                // rift + its dimdev mixin fork — www.dimdev.org is
                // offline; wagyourtail releases mirrors both under
                // org.dimdev (group-scoped so it can't shadow)
                LoaderRepo.Maven(
                    "https://maven.wagyourtail.xyz/releases/",
                    includeGroupRegex = "org\\.dimdev"),
                // launchwrapper 1.12 backup — primary host is
                // libraries.minecraft.net (always in env repos)
                LoaderRepo.Maven("https://maven.minecraftforge.net/"),
                // org.spongepowered artifacts users may layer on
                // (1.x added sponge maven for mixin; the dimdev
                // fork resolves from the mirror above)
                LoaderRepo.Maven("https://repo.spongepowered.org/maven/")
            ),
            // rift versions are MC-keyed (org.dimdev:rift:1.13.2) —
            // an empty declared version means the env's MC version
            loaderCoords = { mc, v -> "org.dimdev:rift:${v.ifEmpty { mc }}" },
            prodNamespace = "official",
            wiringVariant = "rift",
            transformPlugin = "xyz.wagyourtail.unimined.transform.jarmod",
            metadata = LoaderMetadataSpec(fileName = "riftmod.json")
        )
    )
}
