package xyz.wagyourtail.unimined.provider.conversion

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
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
        // shared with the transform-time producer cacheDir
        // (UniminedCache.global/native-cache) — project-local roots made
        // every download run twice per cold build
        val cacheDir = ctx.globalCacheDir.resolve("native-cache")
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
            manifestUrls = spec.manifestUrls,
            baseVersionId = if (product.mergeBaseLibraries &&
                    product.manifestUrl != null) product.baseVersion else null,
            documentPatch = product.versionDocPatch?.let { fn ->
                { doc -> fn(version, doc) }
            },
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
        /** Fetch the non-env side's dist too on sided envs —
         *  FoxLoader 2.x's unified patcher (`patchSlimJar`) requires
         *  the full client+server class set present to patch a jar,
         *  so the env's patch step builds a superset input from both
         *  dists and strips the other side back out. The extra jar
         *  rides `artifacts[otherSideKey]`, never the classpath. */
        val fetchBothSides: Boolean = false,
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
        /** For `manifestUrl` products riding a thin manifest — merge
         *  the base version's `libraries` the manifest doesn't itself
         *  declare (BTA's manifest only carries the launchwrapper set;
         *  the paulscode/sound and other runtime libs live on `b1.7.3`'s
         *  Mojang doc). `versionDocPatch` then rewrites the merged set. */
        val mergeBaseLibraries: Boolean = false,
        /** A post-parse rewrite of the fetched root version document,
         *  bound to `version` at [materialize] — for a stale manifest
         *  whose declared libraries are wrong for a specific version
         *  (BTA `7.3_04` still declares LWJGL2 `*-babric` libs; that
         *  build of the game requires the LWJGL3 set). Rewriting the
         *  doc — not adding to the classpath — so natives resolution
         *  follows the swapped set too. */
        val versionDocPatch: ((version: String, doc: JsonObject) -> JsonObject)? = null,
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
                // foxloader's patcher needs both dists even for a
                // sided env — see fetchBothSides
                fetchBothSides = true,
                // the b1.7.3 doc's `asm-all:4.1` rejects every class
                // file >v51 (`ClassReader.<init>` IAE) — reindev 2.9
                // and FoxLoader's runtime patcher are all v52+. It is
                // a launch-era coremod lib nothing at runtime calls;
                // foxloader's own pom supplies asm 9.11 instead.
                versionDocPatch = { _, doc ->
                    val libs = (doc["libraries"] as? JsonArray)
                        ?.filterIsInstance<JsonObject>()
                        ?.filter {
                            !(it["name"]?.jsonPrimitive?.content
                                ?: "").startsWith("org.ow2.asm:asm-all")
                        }
                    if (libs == null) doc else JsonObject(
                        doc.toMutableMap().apply {
                            put("libraries", JsonArray(libs))
                        }
                    )
                },
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
                    // paulscode.sound.* lived inside the obf b1.7.3 jar
                    // itself — never declared by any version doc, and not
                    // shaded into the deobf dist either. The patched
                    // Paulscode-SoundSystem fork (com.paulscode:* on the
                    // wagyourtail snapshots maven) supplies them.
                    // Deliberately NO LibraryLWJGLOpenAL: the dist bundles
                    // its own net.betterthanadventure.sound.LibraryLWJGL3-
                    // OpenAL binding, and the paulscode lwjgl2 plugin's pom
                    // would drag org.lwjgl.lwjgl:lwjgl:2.9.4 back onto the
                    // classpath (the collision btaDoc73 just removed).
                    // jorbis+jogg arrive transitively via CodecJOrbis.
                    "com.paulscode:SoundSystem:1.0.0-SNAPSHOT",
                    "com.paulscode:LibraryJavaSound:1.0.0-SNAPSHOT",
                    "com.paulscode:CodecWAV:1.0.0-SNAPSHOT",
                    "com.paulscode:CodecJOrbis:1.0.0-SNAPSHOT",
                ),
                manifestUrl = { v ->
                    "https://downloads.betterthanadventure.net/" +
                            "bta-client/release/v$v/manifest.json"
                },
                // thin manifest — only the launchwrapper set; the
                // paulscode/sound + other runtime libs live on the
                // b1.7.3 doc
                mergeBaseLibraries = true,
                // 7.3 was the LWJGL3 switch, but the published 7.3_04
                // manifest still declares the `*-babric` LWJGL2 set —
                // the game refuses with "requires LWJGL 3". Swap the
                // doc's library set (drop lwjgl2/jinput/jutils, add the
                // org.lwjgl:*:3.3.3 set with natives classifiers) so
                // classpath AND natives follow the real requirement.
                versionDocPatch = { v, doc ->
                    if (v == "7.3_04") btaDoc73(doc) else doc
                },
            ),
            "nsss" to ConversionProduct(baseVersion = "b1.7.3"),
        )

        private val docJson = Json { ignoreUnknownKeys = true }

        /** The `org.lwjgl:*:3.3.3` modules BTA 7.3_04 actually needs —
         *  the `*-babric` LWJGL2 set the stale manifest declares can't
         *  serve it (the game hard-checks). Also bumps `gson` off the
         *  b1.7.3-era `2.2.4` the manifest pins — the loader calls
         *  `JsonParser.parseString` (gson ≥2.8.6). Entries carry the
         *  artifact + `natives-<os>` classifiers the Mojang-schema
         *  natives resolution reads, off `libraries.minecraft.net`. */
        private fun btaDoc73(doc: JsonObject): JsonObject {
            // Knot replaces launchwrapper — keeping it pulls the whole
            // b1.7.3-era lwjgl2 set back in transitively (its pom deps
            // jinput/jutils/lwjgl/lwjgl-platform) even though the
            // manifest's own lwjgl2 entries are dropped
            val drop = listOf(
                "org.lwjgl.lwjgl:", "net.java.jinput:", "net.java.jutils:",
                "net.minecraft:launchwrapper", "com.google.code.gson:",
            )
            val modules = listOf(
                "lwjgl", "lwjgl-glfw", "lwjgl-opengl",
                "lwjgl-stb", "lwjgl-openal", "lwjgl-tinyfd",
            )
            val base = "https://libraries.minecraft.net"
            fun lib(module: String): JsonObject {
                val path = "org/lwjgl/$module/3.3.3"
                fun dl(suffix: String) = buildJsonObject {
                    put("path", "$path/$module-3.3.3$suffix.jar")
                    put("url", "$base/$path/$module-3.3.3$suffix.jar")
                }
                return buildJsonObject {
                    put("name", "org.lwjgl:$module:3.3.3")
                    putJsonObject("downloads") {
                        putJsonObject("artifact") {
                            put("path", "$path/$module-3.3.3.jar")
                            put("url", "$base/$path/$module-3.3.3.jar")
                        }
                        putJsonObject("classifiers") {
                            put("natives-linux", dl("-natives-linux"))
                            put("natives-osx", dl("-natives-osx"))
                            put("natives-windows", dl("-natives-windows"))
                        }
                    }
                    putJsonObject("natives") {
                        put("linux", "natives-linux")
                        put("osx", "natives-osx")
                        put("windows", "natives-windows")
                    }
                }
            }
            val keep = (doc["libraries"] as? JsonArray)
                ?.filterIsInstance<JsonObject>()
                ?.filter { el ->
                    val n = el["name"]?.jsonPrimitive?.content ?: ""
                    drop.none { n.startsWith(it) }
                } ?: emptyList()
            // `JsonParser.parseString` needs gson ≥2.8.6 — the manifest's
            // b1.7.3-era 2.2.4 predates it. Maven-central artifact.
            val gson = buildJsonObject {
                put("name", "com.google.code.gson:gson:2.10.1")
                putJsonObject("downloads") {
                    putJsonObject("artifact") {
                        put(
                            "path",
                            "com/google/code/gson/gson/2.10.1/gson-2.10.1.jar"
                        )
                        put(
                            "url",
                            "https://repo1.maven.org/maven2/com/google/" +
                                    "code/gson/gson/2.10.1/gson-2.10.1.jar"
                        )
                    }
                }
            }
            return JsonObject(doc.toMutableMap().apply {
                put(
                    "libraries",
                    JsonArray(
                        buildList {
                            addAll(keep)
                            addAll(modules.map(::lib))
                            add(gson)
                        }
                    )
                )
            })
        }
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
            val needed = if (spec.side == Side.MERGED ||
                    product.fetchBothSides) {
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
                        val bytes = uriFetch(declared.url)
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

        val needed = if (spec.side == Side.MERGED ||
                product.fetchBothSides) {
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

    private fun uriFetch(url: String): ByteArray =
        java.net.URI.create(url).toURL().openStream().use { it.readBytes() }

}
