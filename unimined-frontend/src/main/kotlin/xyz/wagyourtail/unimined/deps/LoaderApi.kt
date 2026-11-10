package xyz.wagyourtail.unimined.deps

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.w3c.dom.Document
import java.io.ByteArrayInputStream
import java.net.URI
import javax.xml.parsers.DocumentBuilderFactory

/**
 * A loader "api" artifact provider — the 2.0 port of 1.x's
 * `FabricLikeApiExtension`, keyed by name instead of a method per
 * loader. Resolves the aggregate api module ([full]) or individual
 * sub-modules ([module]) by reading the api artifact's pom (and, for
 * Ornithe, its meta endpoint).
 */
interface LoaderApiProvider {

    /** Mod id recorded into `mod { depends }` when this api is used. */
    val modid: String

    /** Pom URL for the api descriptor at [version]. */
    fun pomUrl(version: String): String

    /** Maven coordinates for the full api at [version]. */
    fun full(version: String, mcVersion: String = "", environment: String? = null): Set<String>

    /** Coordinate of one api sub-[module], or null when absent. */
    fun module(module: String, version: String, mcVersion: String = "", environment: String? = null): String?

    /**
     * `mod { depends }` entries recorded when this api is used.
     * Defaults to `{modid: version}` — providers whose umbrella
     * version isn't a real mod version (module-split apis like OSL)
     * override to point at a concrete mod id.
     */
    fun dependsSpec(version: String, mcVersion: String = ""): Map<String, String> =
        mapOf(modid to version)
}

/** [LoaderApiProvider] whose sub-modules are the pom's `<dependency>`
 *  elements (fabric, quilt, legacy-fabric, stationapi, qsl). */
open class PomModulesProvider(
    override val modid: String,
    private val artifact: String,
    private val pomPattern: String,
) : LoaderApiProvider {

    override fun pomUrl(version: String) = pomPattern.replace("<v>", version)

    override fun full(version: String, mcVersion: String, environment: String?): Set<String> =
        setOf("$artifact:$version")

    override fun module(module: String, version: String, mcVersion: String, environment: String?): String? {
        val deps = LoaderApi.pom(pomUrl(version)).getElementsByTagName("dependency")
        var group: String? = null
        var vers: String? = null
        var hit = false
        for (i in 0 until deps.length) {
            hit = false
            for (j in 0 until deps.item(i).childNodes.length) {
                val c = deps.item(i).childNodes.item(j)
                when (c.nodeName) {
                    "artifactId" -> hit = c.textContent == module
                    "groupId" -> group = c.textContent
                    "version" -> vers = c.textContent
                }
            }
            if (hit) return "$group:$module:$vers"
        }
        return null
    }
}

/** StationAPI — module selection branches `snapshots`/`releases`. */
class StationApiProvider(branch: String) : PomModulesProvider(
    "stationapi",
    "net.modificationstation:StationAPI",
    "https://maven.glass-launcher.net/$branch/net/modificationstation/StationAPI/<v>/StationAPI-<v>.pom",
)

/** Ornithe OSL — module list comes from the aggregate pom; the
 *  per-MC-version module coordinate comes from meta.ornithemc.net. */
class OslApiProvider : PomModulesProvider(
    "osl",
    "net.ornithemc:osl",
    "https://maven.ornithemc.net/releases/net/ornithemc/osl/<v>/osl-<v>.pom",
) {

    private fun moduleList(version: String): Map<String, String> {
        // OSL gen-split at 0.17+: the umbrella pom moved from `osl` to
        // `osl-gen1`/`osl-gen2` (the pom still lists `module -> version`
        // deps either way; meta resolves each module's mc build).
        for (artifact in listOf("osl", "osl-gen2", "osl-gen1")) {
            val url =
                "https://maven.ornithemc.net/releases/net/ornithemc/" +
                        "$artifact/$version/$artifact-$version.pom"
            val pom = runCatching { LoaderApi.pom(url) }.getOrNull()
                ?: continue
            val deps = pom.getElementsByTagName("dependency")
            val out = linkedMapOf<String, String>()
            for (i in 0 until deps.length) {
                var name: String? = null
                var vers: String? = null
                for (j in 0 until deps.item(i).childNodes.length) {
                    val c = deps.item(i).childNodes.item(j)
                    if (c.nodeName == "artifactId") name = c.textContent
                    if (c.nodeName == "version") vers = c.textContent
                }
                if (name != null && vers != null) out[name] = vers
            }
            if (out.isNotEmpty()) return out
        }
        return emptyMap()
    }

    private fun forMc(module: String, version: String, mc: String, environment: String?): String? {
        val url = "https://meta.ornithemc.net/v3/versions/osl/$module/$mc/$version"
        for (el in Json.parseToJsonElement(LoaderApi.fetch(url)).jsonArray) {
            val mv = el.jsonObject.getValue("version").jsonPrimitive.content
            val sided = "client" in mv || "server" in mv
            if (sided && !environment.isNullOrBlank() && environment !in mv) continue
            return el.jsonObject.getValue("maven").jsonPrimitive.content
        }
        return null
    }

    override fun full(version: String, mcVersion: String, environment: String?): Set<String> =
        moduleList(version).map { (m, v) -> forMc(m, v, mcVersion, environment) }.filterNotNull().toSet()

    override fun module(module: String, version: String, mcVersion: String, environment: String?): String? =
        moduleList(version)[module]?.let { forMc(module, it, mcVersion, environment) }

    override fun dependsSpec(version: String, mcVersion: String): Map<String, String> {
        // OSL is module-split at every gen — there is no `osl`
        // aggregate modid. `osl-core` is the base every module
        // transitively depends on; pin the mc-resolved core build
        // when meta answers, else accept any version.
        val coreV = moduleList(version)["core"] ?: return mapOf("osl-core" to "*")
        val resolved = if (mcVersion.isBlank()) null else runCatching {
            forMc("core", coreV, mcVersion, null)
        }.getOrNull()?.split(":")?.getOrNull(2)
        return mapOf("osl-core" to (resolved?.let { ">=$it" } ?: "*"))
    }
}

/**
 * Registry of loader api providers — `unimined.loaderApi(...)` /
 * `loaderApiModule(...)` delegate here. [fetch] is injectable for
 * tests (default: plain URL fetch).
 */
object LoaderApi {

    /** `url -> bytes` — injectable for tests / offline resolution. */
    var fetch: (String) -> String = { URI.create(it).toURL().readBytes().decodeToString() }

    private val pomCache = mutableMapOf<String, Document>()

    /** Parsed pom document for [url] (cached). */
    internal fun pom(url: String): Document = pomCache.getOrPut(url) {
        ByteArrayInputStream(fetch(url).encodeToByteArray()).use {
            DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(it)
        }
    }

    /** name -> provider. Extend by registering a new
     *  [LoaderApiProvider] under the loader/api name. */
    val providers: MutableMap<String, LoaderApiProvider> = mutableMapOf(
        "fabric" to PomModulesProvider(
            "fabric-api",
            "net.fabricmc.fabric-api:fabric-api",
            "https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/<v>/fabric-api-<v>.pom",
        ),
        "quilt" to PomModulesProvider(
            "qfab",
            "org.quiltmc.quilted-fabric-api:quilted-fabric-api",
            "https://maven.quiltmc.org/repository/release/org/quiltmc/quilted-fabric-api/quilted-fabric-api/<v>/quilted-fabric-api-<v>.pom",
        ),
        "qsl" to PomModulesProvider(
            "qsl",
            "org.quiltmc:qsl",
            "https://maven.quiltmc.org/repository/release/org/quiltmc/qsl/<v>/qsl-<v>.pom",
        ),
        "legacyfabric" to PomModulesProvider(
            "legacy-fabric-api",
            "net.legacyfabric.legacy-fabric-api:legacy-fabric-api",
            "https://maven.legacyfabric.net/net/legacyfabric/legacy-fabric-api/legacy-fabric-api/<v>/legacy-fabric-api-<v>.pom",
        ),
        "stationapi" to StationApiProvider("releases"),
        "stationapi-snapshots" to StationApiProvider("snapshots"),
        "osl" to OslApiProvider(),
    )

    /** All coordinates for loader api [name] at [version]. */
    fun api(name: String, version: String, mcVersion: String = "", environment: String? = null): Set<String> {
        val p = providers[name] ?: throw IllegalArgumentException(
            "unknown loader api '$name' — known: ${providers.keys.joinToString()}")
        return p.full(version, mcVersion, environment)
    }

    /** Coordinate for one api sub-module, erroring like 1.x when absent. */
    fun apiModule(name: String, module: String, version: String, mcVersion: String = "", environment: String? = null): String {
        val p = providers[name] ?: throw IllegalArgumentException(
            "unknown loader api '$name' — known: ${providers.keys.joinToString()}")
        return p.module(module, version, mcVersion, environment)
            ?: throw IllegalStateException("no $name api module '$module' at $version")
    }
}
