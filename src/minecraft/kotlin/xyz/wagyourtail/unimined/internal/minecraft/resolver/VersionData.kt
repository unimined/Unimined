package xyz.wagyourtail.unimined.internal.minecraft.resolver

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import org.gradle.api.JavaVersion
import xyz.wagyourtail.unimined.util.OSUtils
import xyz.wagyourtail.unimined.util.consumerApply
import java.io.File
import java.net.MalformedURLException
import java.net.URI
import java.nio.file.Path
import java.nio.file.Paths
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder


/*
 * Ported from https://github.com/wagyourtail/WagYourLauncher/blob/main/src/main/java/xyz/wagyourtail/launcher/minecraft/version/Version.java
 */
data class VersionData(
    val id: String,
    val type: String?,
    val time: Long,
    val releaseTime: Long,
    val minimumLauncherVersion: Int,
    val mainClass: String?,
    val assetIndex: AssetIndex?,
    val assets: String?,
    val downloads: Map<String, Download>,
    val javaVersion: JavaVersion,
    val arguments: Arguments?,
    val minecraftArguments: String?,
    val libraries: List<Library>,
) {


    fun getJVMArgs(): List<String> {
        val args = mutableListOf<String>()
        args.addAll(
            "-Xmx2G -XX:+UnlockExperimentalVMOptions -XX:+UseG1GC -XX:G1NewSizePercent=20 -XX:G1ReservePercent=20 -XX:MaxGCPauseMillis=50 -XX:G1HeapRegionSize=32M".split(
                " "
            )
        )
        if (arguments?.jvm != null) {
            arguments.jvm.forEach(consumerApply {
                if (rules.all { it.testRule() }) {
                    args.addAll(value)
                }
            })
        } else {
            // default args
            // default args
            val arguments: List<Argument> = listOf(
                Argument(
                    listOf(
                        Rule("allow", OperatingSystem("osx", null, null), mapOf())
                    ), listOf(
                        "-XstartOnFirstThread"
                    )
                ),
                Argument(
                    listOf(
                        Rule("allow", OperatingSystem("windows", null, null), mapOf())
                    ), listOf(
                        "-XX:HeapDumpPath=MojangTricksIntelDriversForPerformance_javaw.exe_minecraft.exe.heapdump"
                    )
                ),
                Argument(
                    listOf(
                        Rule("allow", OperatingSystem("windows", "^10\\.", null), mapOf())
                    ), listOf(
                        "-Dos.name=Windows 10",
                        "-Dos.version=10.0"
                    )
                ),
                Argument(
                    listOf(
                        Rule("allow", OperatingSystem(null, null, "x86"), mapOf())
                    ), listOf(
                        "-Xss1M"
                    )
                ),
                Argument(
                    listOf(),
                    listOf(
                        "-Djava.library.path=\${natives_directory}"
                    )
                ),
                Argument(
                    listOf(), listOf(
                        "-Dminecraft.launcher.brand=\${launcher_name}"
                    )
                ),
                Argument(
                    listOf(), listOf(
                        "-Dminecraft.launcher.version=\${launcher_version}"
                    )
                )
            )

            arguments.forEach(consumerApply {
                if (rules.all { it.testRule() }) {
                    args.addAll(value)
                }
            })
        }


        return args.mapNotNull { e: String ->
            if (e == "\${classpath}" || e == "-cp") null
            else e.replace("\${launcher_name}", "UniminedDev")
                .replace("\${launcher_version}", "1.0.0")
                .replace("\${version_name}", id)
                .replace("\${classpath_separator}", File.pathSeparator)
        }
    }

    private fun getArgsRecursive(): List<String> {
        val args: MutableList<String> = ArrayList()
        if (minecraftArguments != null) {
            args.addAll(minecraftArguments.split(" ").dropLastWhile { it.isEmpty() })
            return args
        }
        if (arguments?.game != null) {
            for (arg in arguments.game) {
                if (arg.rules.all { it.testRule() }) {
                    args.addAll(arg.value)
                }
            }
        }
//        if (inheritsFrom != null) {
//            args.addAll(inheritsFrom.getArgsRecursive(launcher))
//        }
        return args
    }

    fun getGameArgs(): MutableList<String> {
        val args = getArgsRecursive()
        return applyGameArgs(
            args,
            assetIndex?.id ?: "",
            id,
            type!!,
        )
    }
}

fun applyGameArgs(
    args: List<String>,
    assetIndex: String,
    id: String,
    type: String,
): MutableList<String> {
    return args.asSequence().map { e: String ->
            e.replace("\${version_name}", id)
            .replace("\${assets_index_name}", assetIndex)
            .replace("\${assets_index}", assetIndex)
            .replace("\${clientid}", "unimined")
            .replace("\${user_type}", "msa")
            .replace("\${version_type}", type)
            .replace("\${user_properties}", "{}")
    }.toMutableList()
}

// 2010-07-08T22:00:00+00:00
val dateTimeFormat: DateTimeFormatter = DateTimeFormatterBuilder()
    .appendPattern("yyyy-MM-dd'T'HH:mm:ss")
    .appendZoneOrOffsetId()
    .toFormatter()


fun parseVersionData(json: JsonObject): VersionData {
    return VersionData(
        json.get("id").asString,
        json.get("type")?.asString,
        json.get("time")?.asString?.let {
            LocalDateTime.parse(it, dateTimeFormat)
                .toInstant(ZoneOffset.UTC)
                .toEpochMilli()
        } ?: 0,
        json.get("releaseTime")?.asString?.let {
            LocalDateTime.parse(it, dateTimeFormat)
                .toInstant(ZoneOffset.UTC)
                .toEpochMilli()
        } ?: 0,
        json.get("minimumLauncherVersion")?.asInt ?: 0,
        json.get("mainClass").let { if (it is JsonNull) null else it.asString },
        json.get("assetIndex")?.asJsonObject?.let { parseAssets(it) },
        json.get("assets")?.asString,
        json.get("downloads").asJsonObject?.let { parseAllDownload(it) } ?: mapOf(),
        json.get("javaVersion")?.asJsonObject?.let { parseJavaVersion(it) } ?: JavaVersion.VERSION_1_8,
        json.get("arguments")?.asJsonObject?.let { parseArguments(it) },
        json.get("minecraftArguments")?.asString,
        json.get("libraries")?.asJsonArray?.let { parseAllLibraries(it) } ?: listOf()
    )
}

data class AssetIndex(
    val id: String?,
    val sha1: String?,
    val url: URI?,
    val size: Long,
    val totalSize: Long
)

fun parseAssets(json: JsonObject): AssetIndex {
    return AssetIndex(
        json.get("id")?.asString,
        json.get("sha1")?.asString,
        try {
            URI(json.get("url")?.asString ?: "")
        } catch (e: MalformedURLException) {
            null
        },
        json.get("size")?.asLong ?: 0,
        json.get("totalSize")?.asLong ?: 0
    )
}

data class Download(
    val sha1: String,
    val size: Long,
    val url: URI?
)

fun parseAllDownload(json: JsonObject): Map<String, Download> {
    return json.entrySet().associate {
        it.key to parseDownload(it.value.asJsonObject)
    }
}

fun parseDownload(json: JsonObject): Download {
    return Download(
        json.get("sha1")?.asString ?: "",
        json.get("size")?.asLong ?: 0,
        try {
            json.get("url")?.asString?.let { URI(it) }
        } catch (e: MalformedURLException) {
            null
        }
    )
}

fun parseJavaVersion(json: JsonObject): JavaVersion {
    return JavaVersion.values()[json.get("majorVersion")?.asInt?.let { it - 1 } ?: 7]
}

data class Arguments(
    val jvm: List<Argument>,
    val game: List<Argument>
)

fun parseArguments(json: JsonObject): Arguments {
    return Arguments(
        json.get("jvm")?.asJsonArray?.let { parseArgumentList(it) } ?: listOf(),
        json.get("game")?.asJsonArray?.let { parseArgumentList(it) } ?: listOf(),
    )
}

fun parseArgumentList(json: JsonArray): List<Argument> {
    return json.map { parseArgument(it) }
}

data class Argument(
    val rules: List<Rule>,
    val value: List<String>
)

fun parseArgument(json: JsonElement): Argument {
    return if (json.isJsonObject) {
        parseArgument(json.asJsonObject)
    } else {
        Argument(
            listOf(),
            listOf(json.asString)
        )
    }
}

fun parseArgument(json: JsonObject): Argument {
    return Argument(
        json.getAsJsonArray("rules")?.map { parseRule(it.asJsonObject) } ?: listOf(),
        json.get("value")?.let { value -> if (value is JsonArray) value.map { it.asString } else listOf(value.asString) } ?: listOf()
    )
}

data class Rule(
    val action: String?,
    val os: OperatingSystem?,
    val features: Map<String, Boolean>
) {
    fun testRule(): Boolean {
        if (os != null && !os.test()) return action != "allow"
        features.forEach { (key, value) ->
            if (listOf<String>().contains(key) != value) return action != "allow"
        }
        return action == "allow"
    }
}

fun parseRule(json: JsonObject): Rule {
    return Rule(
        json.get("action")?.asString,
        json.get("os")?.asJsonObject?.let { parseOperatingSystem(it) },
        json.get("features")?.asJsonObject?.entrySet()?.associate { it.key to it.value.asBoolean } ?: mapOf()
    )
}

data class OperatingSystem(
    val name: String?,
    val version: String?,
    val arch: String?
) {
    fun test(): Boolean {
        if (name != null && name != OSUtils.oSId) return false
        return if ((version != null) && !OSUtils.osVersion.contains(Regex(version)))
            false
        else
            arch == null || arch == OSUtils.osArch
    }
}

fun parseOperatingSystem(json: JsonObject): OperatingSystem {
    return OperatingSystem(
        json.get("name")?.asString,
        json.get("version")?.asString,
        json.get("arch")?.asString
    )
}

data class Library(
    val downloads: Downloads?,
    val name: String,
    val url: URI?,
    val natives: Map<String, String>,
    val extract: Extract?,
    val rules: List<Rule>
)

fun parseAllLibraries(json: JsonArray): List<Library> {
    return json.map { parseLibrary(it.asJsonObject) }
}

fun parseLibrary(json: JsonObject): Library {
    return Library(
        json.get("downloads")?.asJsonObject?.let { parseDownloads(it) },
        json.get("name")?.asString ?: "",
        try {
            json.get("url")?.asString?.let { URI(it) }
        } catch (e: MalformedURLException) {
            null
        },
        json.get("natives")?.asJsonObject?.entrySet()
            ?.associate { it.key to it.value.asString.replace("\${arch}", OSUtils.osArchNum) } ?: mapOf(),
        json.get("extract")?.asJsonObject?.let { parseExtract(it) },
        json.getAsJsonArray("rules")?.map { parseRule(it.asJsonObject) } ?: listOf()
    )
}

data class Downloads(
    val artifact: Artifact?,
    val classifiers: Map<String, Artifact>
)

fun parseDownloads(json: JsonObject): Downloads {
    return Downloads(
        json.get("artifact")?.asJsonObject?.let { parseArtifact(it) },
        json.get("classifiers")?.asJsonObject?.entrySet()?.associate { it.key to parseArtifact(it.value.asJsonObject) }
            ?: mapOf()
    )
}

data class Artifact(
    val path: Path?,
    val sha1: String?,
    val size: Long,
    val url: URI?
)

fun parseArtifact(json: JsonObject): Artifact {
    return Artifact(
        json.get("path")?.asString?.let { Paths.get(it) },
        json.get("sha1")?.asString,
        json.get("size")?.asLong ?: 0,
        try {
            json.get("url")?.asString?.let { URI(it) }
        } catch (e: MalformedURLException) {
            null
        }
    )
}

data class Extract(
    val exclude: List<String>
)

fun parseExtract(json: JsonObject): Extract {
    return Extract(
        json.getAsJsonArray("exclude")?.map { it.asString } ?: listOf()
    )
}