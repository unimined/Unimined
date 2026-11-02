package xyz.wagyourtail.unimined.provider.conversion

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import org.gradle.api.GradleException
import xyz.wagyourtail.commonskt.utils.toHex
import xyz.wagyourtail.unimined.api.spec.DeclaredSource
import xyz.wagyourtail.unimined.api.spec.ProductSpec
import xyz.wagyourtail.unimined.api.spec.Side
import xyz.wagyourtail.unimined.minecraft.spi.ProductArtifacts
import xyz.wagyourtail.unimined.minecraft.spi.ProductProvider
import xyz.wagyourtail.unimined.minecraft.spi.ProviderContext
import xyz.wagyourtail.unimined.minecraft.download.Downloader
import xyz.wagyourtail.unimined.minecraft.manifest.ManifestResolver
import xyz.wagyourtail.unimined.minecraft.manifest.VersionJson
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.time.Duration

/**
 * Total-conversion provider — `provider("conversion")`. The dist jar
 * IS the complete Minecraft jar (reindev / bta / nsss): no vanilla
 * manifest, no remap — `prodNamespace` is `official` throughout and
 * the env rides the identity namespace path.
 *
 * Claims specs whose loader is a conversion product. Sided dists
 * resolve client+server jars separately; a MERGED env fetches both
 * and rides the standard client/server merge machinery.
 *
 * Artifact + launch sources per product:
 *  - `reindev`: `cdn.fox2code.com/files/reindev-{client,server}-{ver}.jar` (2.8.1_5+)
 *    or `reindev-{ver}-{client,server}.jar` (≤2.4.x) — both patterns tried;
 *    launch doc is Mojang's `b1.7.3` version JSON (1.x `baseVersion`)
 *  - `bta`: `downloads.betterthanadventure.net/bta-{client,server}/
 *    release/v{ver}/` — the per-version `manifest.json` IS a complete
 *    Mojang-schema version document (libraries, sided downloads with
 *    sha1, assetIndex, mainClass)
 *  - `nsss`: declared file/URL source only (no upstream index); launch
 *    doc rides the base version like reindev
 */
class ConversionProvider : ProductProvider {

    override val name: String = "conversion"

    /** Outranks the catch-all `native` provider when capability
     *  selection runs without a `provider(...)` override. */
    override val priority: Int = 10

    override fun supports(spec: ProductSpec): Boolean =
        spec.product.removePrefix("minecraft:") in PRODUCTS

    override fun resolve(spec: ProductSpec, ctx: ProviderContext): ProductArtifacts {
        val productId = spec.product.removePrefix("minecraft:")
        val product = PRODUCTS[productId]
            // unknown `minecraft:`-family products resolve only via a
            // declared source, on the spec's own version's launch doc
            ?: ConversionProduct(baseVersion = spec.version.version)
        require(spec.side != Side.MERGED ||
                spec.declaredSource != null ||
                product.mergeable) {
            "conversion '$productId' can't produce a merged " +
                    "artifact — its dists are independently-built forks " +
                    "with divergent shared classes; declare side = " +
                    "CLIENT or SERVER (upstream ships real sided jars)"
        }
        val cacheDir = ctx.layout.projectDirectory
            .dir(".gradle/unimined/conversion-cache").asFile.toPath()
        val downloader = Downloader(
            cacheDir = cacheDir,
            offline = ctx.offline,
            refreshCache = ctx.refreshUniminedDownloads,
            metadataTtl = ctx.metadataCacheTtl ?: Duration.ofHours(24),
            onRefreshFallback = { file, error ->
                ctx.logger.warn(
                    "[Unimined/Cache] Using cached $file after refresh failed: ${error.message}"
                )
            }
        )
        val resolver = ManifestResolver(
            downloader = downloader,
            metadataTtlOverride = ctx.metadataCacheTtl,
            manifestUrls = listOf(ManifestResolver.VERSION_MANIFEST_V2) + spec.manifestUrls
        )
        val version = spec.version.version
        val launch = ConversionLaunchMetadata(
            rootId = if (product.manifestUrl != null) "$productId-$version"
                     else product.baseVersion,
            rootDocumentUrl = product.manifestUrl?.invoke(version),
            cacheDir = cacheDir,
            offline = ctx.offline,
            refreshCache = ctx.refreshUniminedDownloads,
            metadataTtlMillis = ctx.metadataCacheTtl?.toMillis(),
            manifestUrls = spec.manifestUrls
        ).withBackendFactory { resolver to downloader }

        return ConversionArtifacts(
            spec,
            ctx.providers.provider {
                materialize(spec, productId, product, downloader, ctx)
            },
            ctx.providers.provider { launch.versionJson(launch.rootId) },
            downloader,
            launch,
            ctx
        )
    }

    /** Per-product artifact + launch-document shape. */
    internal data class ConversionProduct(
        /** Mojang version id serving the launch doc when the product
         *  publishes no manifest of its own (1.x `baseVersion`). */
        val baseVersion: String,
        /** Version-document URL — the doc's `downloads` block carries
         *  the sided jars (`bta`). */
        val manifestUrl: ((String) -> String)? = null,
        /** Sided dist-jar URL templates, tried in order — dists
         *  renamed their artifact scheme mid-line (`reindev-v-side`
         *  → `reindev-side-v`), so a product lists each pattern its
         *  versions may live under. */
        val clientUrls: List<(String) -> String> = emptyList(),
        val serverUrls: List<(String) -> String> = emptyList(),
        /** Whether the sided dists survive JarMerger's member-merge —
         *  an empirical property (ConversionMergeProbeTest): total-
         *  conversion dists are independently-built forks, some merge
         *  (bta), some diverge (reindev). */
        val mergeable: Boolean = false,
        /** `group:artifact:version` deps declared in addition to the
         *  manifest libraries — for libraries the dists shade into
         *  the jar (bta embeds SLF4J + log4j2, its manifest declares
         *  none). Declared deps resolve onto the run classpath AND
         *  drop their bundled copies out of merged artifacts via the
         *  membership strip (stripBundled*Libraries). Versions are
         *  pinned to the shaded vintage — a binding/version skew
         *  silently breaks SPI wiring (slf4j-2.x api needs the 2.x
         *  log4j impl binding). */
        val extraLibraries: List<String> = emptyList(),
    )

    companion object {
        /** Whether the product declares `extraLibraries` — the
         *  bundled-library strip gate for env wiring
         *  (transform-conversion can't see the product table's
         *  internals; this is its declared-replacements probe). */
        @JvmStatic
        fun declaresExtraLibraries(productId: String): Boolean =
            PRODUCTS[productId.removePrefix("minecraft:")]
                ?.extraLibraries?.isNotEmpty() == true

        internal val PRODUCTS = mapOf(
            "reindev" to ConversionProduct(
                baseVersion = "b1.7.3",
                clientUrls = listOf(
                    // 2.8.1_5+/2.9-era naming — the FoxLoader-compatible
                    // line (see transform-conversion's version map)
                    { v -> "https://cdn.fox2code.com/files/reindev-client-$v.jar" },
                    // 2.4.x-era naming
                    { v -> "https://cdn.fox2code.com/files/reindev-$v-client.jar" },
                ),
                serverUrls = listOf(
                    { v -> "https://cdn.fox2code.com/files/reindev-server-$v.jar" },
                    { v -> "https://cdn.fox2code.com/files/reindev-$v-server.jar" },
                ),
            ),
            "bta" to ConversionProduct(
                baseVersion = "b1.7.3",
                mergeable = true, // JarMerger keeps META-INF/services + client-wins resources — SLF4J binds log4j2 again
                extraLibraries = listOf(
                    // shaded into the dist jars (~Sept 2022 vintage);
                    // declared so the copies strip out of merges and
                    // the classpath carries managed versions
                    "org.slf4j:slf4j-api:2.0.3",
                    "org.apache.logging.log4j:log4j-api:2.19.0",
                    "org.apache.logging.log4j:log4j-core:2.19.0",
                    "org.apache.logging.log4j:log4j-slf4j2-impl:2.19.0",
                ),
                manifestUrl = { v ->
                    "https://downloads.betterthanadventure.net/" +
                            "bta-client/release/v$v/manifest.json"
                },
            ),
            "nsss" to ConversionProduct(baseVersion = "b1.7.3"),
        )

        private val docJson = Json { ignoreUnknownKeys = true }
    }

    private fun materialize(
        spec: ProductSpec,
        loader: String,
        product: ConversionProduct,
        downloader: Downloader,
        ctx: ProviderContext
    ): Map<Side, File> {
        val version = spec.version.version
        product.manifestUrl?.let { template ->
            // the manifest's downloads block is the jar source of truth —
            // sha1-verified sided entries
            val doc: VersionJson = docJson.decodeFromJsonElement(
                Json.parseToJsonElement(
                    Files.readString(
                        downloader.fetchMetadata(
                            template(version),
                            "metadata/conversion/$loader-$version.json",
                            ctx.metadataCacheTtl ?: Duration.ofDays(7)
                        )
                    )
                )
            )
            val needed = if (spec.side == Side.MERGED) {
                setOf(Side.CLIENT, Side.SERVER)
            } else setOf(spec.side)
            return needed.mapNotNull { side ->
                val ref = doc.downloads?.get(
                    if (side == Side.CLIENT) "client" else "server"
                ) ?: return@mapNotNull null
                side to downloader.fetch(
                    ref.url,
                    "$loader/$version/${side.name.lowercase()}.jar",
                    ref.sha1?.lowercase()
                ).toFile()
            }.toMap()
        }

        if (product.clientUrls.isEmpty()) {
            // nsss (or a future declared-source dist): the env must
            // carry a declaredSource
            val declared = spec.declaredSource?.source
                ?: throw GradleException(
                    "conversion '$loader' has no upstream artifact source;" +
                            " declare a legacydist source (file/url)"
                )
            val jar = downloader.cacheDir.resolve("declared/$loader-$version.jar")
                .toFile().apply { parentFile.mkdirs() }
            when (declared) {
                is DeclaredSource.LocalFile -> {
                    val src = File(declared.path).takeIf { it.exists() }
                        ?: ctx.layout.projectDirectory.file(declared.path).asFile
                    if (!src.exists()) throw GradleException(
                        "conversion declared file not found: ${declared.path}"
                    )
                    src.copyTo(jar, overwrite = true)
                }
                is DeclaredSource.Url -> {
                    if (jar.exists() && !ctx.refreshUniminedDownloads && !ctx.offline) {
                        // reused
                    } else {
                        val bytes = URI_fetch(declared.url)
                        declared.sha256?.let { expected ->
                            val actual = MessageDigest.getInstance("SHA-256")
                                .digest(bytes).toHex()
                            if (!actual.equals(expected, ignoreCase = true)) {
                                throw GradleException(
                                    "sha256 mismatch for ${declared.url} — expected $expected"
                                )
                            }
                        }
                        jar.writeBytes(bytes)
                    }
                }
                else -> throw GradleException(
                    "conversion '$loader' supports file/url declared sources only"
                )
            }
            return mapOf(spec.side to jar)
        }

        val needed = if (spec.side == Side.MERGED) {
            setOf(Side.CLIENT, Side.SERVER)
        } else setOf(spec.side)
        return needed.associateWith { side ->
            val templates = if (side == Side.CLIENT) product.clientUrls
            else product.serverUrls
            if (templates.isEmpty()) throw GradleException(
                "conversion '$loader' has no $side artifact for $version"
            )
            fetchFirstOf(
                downloader,
                templates.map { it(version) },
                "$loader/$version/${side.name.lowercase()}.jar"
            ).toFile()
        }
    }

    private fun URI_fetch(url: String): ByteArray =
        java.net.URI.create(url).toURL().openStream().use { it.readBytes() }

}
