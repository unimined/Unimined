@file:Suppress("unused")

package xyz.wagyourtail.unimined.util

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipFile
import org.apache.commons.io.output.NullOutputStream
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.FileCollectionDependency
import org.gradle.api.artifacts.ModuleDependency
import org.gradle.api.artifacts.component.ComponentArtifactIdentifier
import org.gradle.api.artifacts.component.ComponentIdentifier
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedArtifactResult
import org.gradle.api.file.FileCollection
import org.gradle.api.logging.LogLevel
import org.gradle.api.logging.configuration.ShowStacktrace
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.process.JavaExecSpec
import xyz.wagyourtail.unimined.api.unimined
import xyz.wagyourtail.unimined.mapping.EnvType
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.*
import java.util.zip.ZipOutputStream
import kotlin.io.path.*
import kotlin.math.pow
import kotlin.reflect.KClass
import kotlin.reflect.KProperty1
import kotlin.reflect.full.declaredMemberProperties
import kotlin.reflect.jvm.isAccessible
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

val EnvType.classifier: String?
    get() {
        return when (this) {
            EnvType.CLIENT -> "client"
            EnvType.SERVER -> "server"
            EnvType.JOINED -> null
        }
    }

val Project.sourceSets
    get() = extensions.findByType(SourceSetContainer::class.java)!!

fun <U : Any> KClass<U>.getField(name: String): KProperty1<U, *>? {
    return declaredMemberProperties.firstOrNull { it.name == name }?.apply {
        isAccessible = true
    }
}

inline fun <T, U> consumerApply(crossinline action: T.() -> U): (T) -> U {
    return { action(it) }
}

fun Configuration.getFiles(dep: Dependency, filter: (File) -> Boolean): FileCollection {
    resolve()
    return incoming.artifactView { view ->
        when (dep) {
            is ModuleDependency -> {
                view.componentFilter {
                    when (it) {
                        is ModuleComponentIdentifier -> {
                            it.group == dep.group && it.module == dep.name
                        }
                        is ComponentArtifactIdentifier -> {
                            false
                        }
                        else -> {
                            println("Unknown component type: ${it.javaClass}")
                            false
                        }
                    }
                }
            }
            is FileCollectionDependency -> {
                view.componentFilter { comp ->
                    when (comp) {
                        is ModuleComponentIdentifier -> {
                            false
                        }
                        is ComponentIdentifier -> {
                            dep.files.any { it.name == comp.displayName }
                        }
                        else -> {
                            println("Unknown component type: ${comp.javaClass}")
                            false
                        }
                    }
                }
            }
            else -> {
                throw IllegalArgumentException("Unknown dependency type: ${dep.javaClass}")
            }
        }
    }.files.filter(filter)
}

fun Configuration.getFiles(dep: Dependency, extension: String = "jar"): FileCollection {
    return getFiles(dep) { it.extension == extension }
}

fun URI.stream(): InputStream {
    val conn = toURL().openConnection()
    conn.setRequestProperty("User-Agent", "Wagyourtail/Unimined 1.0 (<wagyourtail@wagyourtail.xyz>)")
    return conn.getInputStream()
}

object OSUtils {
    val oSId: String
        get() {
            val osName = System.getProperty("os.name").lowercase(Locale.getDefault())
            if (osName.contains("darwin") || osName.contains("mac")) {
                return "osx"
            }
            if (osName.contains("win")) {
                return "windows"
            }
            return if (osName.contains("nux")) {
                "linux"
            } else "unknown"
        }
    val osVersion: String
        get() = System.getProperty("os.version")
    val osArch: String
        get() = System.getProperty("os.arch")

    val osArchNum: String
        get() = when (osArch) {
            "x86" -> "32"
            "i386" -> "32"
            "i686" -> "32"
            "amd64" -> "64"
            "x86_64" -> "64"
            else -> "unknown"
        }

    const val WINDOWS = "windows"
    const val LINUX = "linux"
    const val OSX = "osx"
    const val UNKNOWN = "unknown"
}

fun Project.cachingDownload(url: String): Path {
    return cachingDownload(uri(url))
}

fun Project.cachingDownload(
    url: URI,
    size: Long = -1L,
    sha1: String? = null,
    cachePath: Path = unimined.getGlobalCache().resolve(url.path.substring(1)),
    ignoreShaOnCache: Boolean = false,
    expireTime: Duration = 1.days,
    retryCount: Int = 3,
    backoff: (Int) -> Int = { 1000 * 3.0.pow(it.toDouble()).toInt() }, // first backoff -> 1s, second -> 3s, third -> 9s
): Path {
    if (gradle.startParameter.isOffline) {
        if (testSha1(size, if (ignoreShaOnCache) null else sha1, cachePath, Duration.INFINITE)) {
            return cachePath
        }
        if (cachePath.exists()) {
            throw IllegalStateException("cached $url at $cachePath doesn't match expected (sha: $sha1, size: $size) and offline mode is enabled")
        } else {
            throw IllegalStateException("cached $url at $cachePath doesn't exist and offline mode is enabled")
        }
    }

    val cacheTime = if (gradle.startParameter.isRefreshDependencies || project.unimined.forceReload) 0.seconds
        else if (ignoreShaOnCache) Duration.INFINITE
        else expireTime

    if (testSha1(
            size,
            if (ignoreShaOnCache) null else sha1,
            cachePath,
            cacheTime
    )) {
        logger.info("[Unimined/Cache] Using cached $url at $cachePath")
        return cachePath
    }

    var exception: Exception? = null
    cachePath.parent?.createDirectories()
    logger.info("[Unimined/Cache] Downloading $url to $cachePath")
    for (i in 1 .. retryCount) {
        try {
            url.stream().use {
                cachePath.outputStream().use { os -> it.copyTo(os) }
            }
        } catch (e: Exception) {
            logger.warn("[Unimined/Cache] Failed to download $url, retrying in ${backoff(i)}ms...")
            if (i == 1) {
                logger.warn("[Unimined/Cache]    If you are offline, please run gradle with \"--offline\"")
            }
            Thread.sleep(backoff(i).toLong())
            exception = e
            continue
        }
        if (testSha1(size, sha1, cachePath)) {
            return cachePath
        }
        logger.warn("[Unimined/Cache] Failed to download $url, retrying in ${backoff(i)}ms...")
        Thread.sleep(backoff(i).toLong())
    }

    // should only happen if ignoreShaOnCache is false
    if (testSha1(size, sha1, cachePath, Long.MAX_VALUE.milliseconds)) {
        logger.warn("[Unimined/Cache] Falling back on expired cache $cachePath for $url")
        return cachePath
    }

    throw IllegalStateException("Failed to download $url", exception)
}

fun testSha1(size: Long, sha1: String?, path: Path, expireTime: Duration = 1.days): Boolean {
    if (path.exists()) {
        if (path.fileSize() == size || size == -1L) {
            if (sha1.isNullOrEmpty()) {
                // fallback: expire if older than a day
                return path.getLastModifiedTime().toMillis() > System.currentTimeMillis() - expireTime.inWholeMilliseconds
            }
            val digestSha1 = MessageDigest.getInstance("SHA-1")
            path.inputStream().use {
                digestSha1.update(it.readBytes())
            }
            val hashBytes = digestSha1.digest()
            val hash = hashBytes.joinToString("") { String.format("%02x", it) }
            if (hash.equals(sha1, ignoreCase = true)) {
                return true
            }
        }
    }
    return false
}

fun Path.getSha1(): String {
    val digestSha1 = MessageDigest.getInstance("SHA-1")
    inputStream().use {
        digestSha1.update(it.readBytes())
    }
    val hashBytes = digestSha1.digest()
    return hashBytes.joinToString("") { String.format("%02x", it) }
}

fun File.getSha1() = toPath().getSha1()

fun Path.getShortSha1(): String = getSha1().substring(0, 7)

fun File.getShortSha1() = toPath().getShortSha1()

fun <K, V> HashMap<K, V>.getSha1(): String {
    val digestSha1 = MessageDigest.getInstance("SHA-1")
    digestSha1.update(toString().toByteArray())
    val hashBytes = digestSha1.digest()
    return hashBytes.joinToString("") { String.format("%02x", it)}
}

fun <K, V> HashMap<K, V>.getShortSha1(): String = getSha1().substring(0, 7)

fun String.getSha1(from: Int = 0, to: Int = 40): String {
    val digestSha1 = MessageDigest.getInstance("SHA-1")
    digestSha1.update(toByteArray())
    val hashBytes = digestSha1.digest()
    return hashBytes.joinToString("") { String.format("%02x", it) }.substring(from, to)
}

fun String.getSha256(from: Int = 0, to: Int = 64): String {
    val digestSha256 = MessageDigest.getInstance("SHA-256")
    digestSha256.update(toByteArray())
    val hashBytes = digestSha256.digest()
    return hashBytes.joinToString("") { String.format("%02x", it) }.substring(from, to)
}

fun String.getShortSha1() = getSha1().substring(0, 7)

//fun runJarInSubprocess(
//    jar: Path,
//    vararg args: String,
//    mainClass: String? = null,
//    workingDir: Path = Paths.get("."),
//    env: Map<String, String> = mapOf(),
//    wait: Boolean = true,
//    jvmArgs: List<String> = listOf()
//): Int? {
//    val javaHome = System.getProperty("java.home")
//    val javaBin = Paths.get(javaHome, "bin", if (OSUtils.oSId == "windows") "java.exe" else "java")
//    if (!javaBin.exists()) {
//        throw IllegalStateException("java binary not found at $javaBin")
//    }
//    val processArgs = if (mainClass == null) {
//        arrayOf("-jar", jar.toString())
//    } else {
//        arrayOf("-cp", jar.toString(), mainClass)
//    } + args
//    val processBuilder = ProcessBuilder(
//        javaBin.toString(),
//        *jvmArgs.toTypedArray(),
//        *processArgs,
//    )
//
//    val logger = LoggerFactory.getLogger(UniminedExtension::class.java);
//
//    processBuilder.directory(workingDir.toFile())
//    processBuilder.environment().putAll(env)
//
//    logger.info("Running: ${processBuilder.command().joinToString(" ")}")
//    val process = processBuilder.start()
//
//    val inputStream = process.inputStream
//    val errorStream = process.errorStream
//
//    val outputThread = Thread {
//        inputStream.copyTo(object : OutputStream() {
//            // buffer and write lines
//            private var line: String? = null
//
//            override fun write(b: Int) {
//                if (b == '\r'.toInt()) {
//                    return
//                }
//                if (b == '\n'.toInt()) {
//                    logger.info(line)
//                    line = null
//                } else {
//                    line = (line ?: "") + b.toChar()
//                }
//            }
//        })
//    }
//
//    val errorThread = Thread {
//        errorStream.copyTo(object : OutputStream() {
//            // buffer and write lines
//            private var line: String? = null
//
//            override fun write(b: Int) {
//                if (b == '\r'.toInt()) {
//                    return
//                }
//                if (b == '\n'.toInt()) {
//                    logger.error(line)
//                    line = null
//                } else {
//                    line = (line ?: "") + b.toChar()
//                }
//            }
//        })
//    }
//
//    outputThread.start()
//    errorThread.start()
//
//    if (wait) {
//        process.waitFor()
//        return process.exitValue()
//    }
//    return null
//}

fun Path.deleteRecursively() {
    Files.walkFileTree(this, object: SimpleFileVisitor<Path>() {
        override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
            dir.deleteExisting()
            return FileVisitResult.CONTINUE
        }

        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
            file.deleteExisting()
            return FileVisitResult.CONTINUE
        }
    })
}

fun Path.forEachFile(action: (Path) -> Unit) {
    Files.walkFileTree(this, object: SimpleFileVisitor<Path>() {
        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
            action(file)
            return FileVisitResult.CONTINUE
        }
    })
}

fun ByteArray.toHex() = joinToString(separator = "") { byte -> "%02x".format(byte) }

fun <T> Optional<T>.orElseOptional(invoke: () -> Optional<T>): Optional<T> {
    return if (isPresent) {
        this
    } else {
        invoke()
    }
}

fun getTempFilePath(prefix: String, suffix: String): Path {
    return Files.createTempFile(prefix, suffix).apply {
        deleteExisting()
    }
}

operator fun StringBuilder.plusAssign(other: String) {
    append(other)
}

fun String.withSourceSet(sourceSet: SourceSet) =
    if (sourceSet.name == "main") this else "${sourceSet.name}${this.capitalized()}"

fun String.decapitalized(): String = if (this.isEmpty()) this else this[0].lowercase() + this.substring(1)

fun String.capitalized(): String = if (this.isEmpty()) this else this[0].uppercase() + this.substring(1)

fun <K, V> Iterable<Pair<K, V>>.associated() = associate { it }

@Suppress("UNCHECKED_CAST")
fun <K, V> Map<K, V?>.nonNullValues(): Map<K, V> = filterValues { it != null } as Map<K, V>

fun <E, K, V> Iterable<E>.associateNonNull(apply: (E) -> Pair<K, V>?): Map<K, V> {
    val mut = mutableMapOf<K, V>()
    for (e in this) {
        apply(e)?.let {
            mut.put(it.first, it.second)
        }
    }
    return mut
}

fun Path.isZip(): Boolean =
    inputStream().use { stream -> ByteArray(4).also { stream.read(it, 0, 4) } }
        .contentEquals(byteArrayOf(0x50, 0x4B, 0x03, 0x04))

fun Path.readZipContents(): List<String> {
    val contents = mutableListOf<String>()
    forEachInZip { entry, _ ->
        contents.add(entry)
    }
    return contents
}

fun Path.forEachInZip(action: (String, InputStream) -> Unit) {
    Files.newByteChannel(this).use { sbc ->
        ZipFile.builder().setIgnoreLocalFileHeader(true).setSeekableByteChannel(sbc).get().use { zip ->
            for (zipArchiveEntry in zip.entries.iterator()) {
                if (zipArchiveEntry.isDirectory) {
                    continue
                }
//                if (zipArchiveEntry.name.isEmpty() && zipArchiveEntry.size == 0L) {
//                    continue
//                }
                zip.getInputStream(zipArchiveEntry).use {
                    action(zipArchiveEntry.name, it)
                }
            }
        }
    }
}

fun Path.forEntryInZip(action: (ZipArchiveEntry, InputStream) -> Unit) {
    Files.newByteChannel(this).use { sbc ->
        ZipFile.builder().setIgnoreLocalFileHeader(true).setSeekableByteChannel(sbc).get().use { zip ->
            for (zipArchiveEntry in zip.entries.iterator()) {
                if (zipArchiveEntry.isDirectory) {
                    continue
                }
//                if (zipArchiveEntry.name.isEmpty() && zipArchiveEntry.size == 0L) {
//                    continue
//                }
                zip.getInputStream(zipArchiveEntry).use {
                    action(zipArchiveEntry, it)
                }
            }
        }
    }
}

fun <T> Path.readZipInputStreamFor(path: String, throwIfMissing: Boolean = true, action: (InputStream) -> T): T {
    Files.newByteChannel(this).use {
        ZipFile.builder().setIgnoreLocalFileHeader(true).setSeekableByteChannel(it).get().use { zip ->
            val entry = zip.getEntry(path.replace("\\", "/"))
            if (entry != null) {
                return zip.getInputStream(entry).use(action)
            } else {
                if (throwIfMissing) {
                    throw IllegalArgumentException("Missing file $path in $this")
                }
            }
        }
    }
    return null as T
}

fun Path.zipContains(path: String): Boolean {
    Files.newByteChannel(this).use {
        ZipFile.builder().setIgnoreLocalFileHeader(true).setSeekableByteChannel(it).get().use { zip ->
            val entry = zip.getEntry(path.replace("\\", "/"))
            if (entry != null) {
                return true
            }
        }
    }
    return false
}

fun Path.openZipFileSystem(vararg args: Pair<String, Any>): FileSystem {
    return openZipFileSystem(args.associate { it })
}

fun Path.openZipFileSystem(args: Map<String, *> = mapOf<String, Any>()): FileSystem {
    if (!exists() && args["create"] == true) {
        ZipOutputStream(outputStream()).use { stream ->
            stream.closeEntry()
        }
    }
    return FileSystems.newFileSystem(URI.create("jar:${toUri()}"), args, null)
}

val CONSTANT_TIME_FOR_ZIP_ENTRIES = GregorianCalendar(1980, Calendar.FEBRUARY, 1, 0, 0, 0).timeInMillis

fun <K, V> MutableMap<K, V>.removeALl(other: Map<K, V>): MutableMap<K, V> {
    other.forEach {
        remove(it.key, it.value)
    }
    return this
}

fun Project.shouldShowVerboseStdout(): Boolean {
    return gradle.startParameter.logLevel < LogLevel.LIFECYCLE
}

fun Project.shouldShowVerboseStderr(): Boolean {
    return shouldShowVerboseStdout() || gradle.startParameter.showStacktrace != ShowStacktrace.INTERNAL_EXCEPTIONS
}

fun Project.suppressLogs(spec: JavaExecSpec) {
    if (shouldShowVerboseStdout()) {
        spec.standardOutput = System.out
    } else {
        spec.standardOutput = NullOutputStream.INSTANCE
    }
    if (shouldShowVerboseStderr()) {
        spec.errorOutput = System.err
    } else {
        spec.errorOutput = NullOutputStream.INSTANCE
    }
}

fun ResolvedArtifactResult.getCoords(): MavenCoords {
    val owner = this.variant.owner

    var location = if (owner is ModuleComponentIdentifier) {
        MavenCoords(owner.group, owner.module, owner.version)
    } else {
        null
    }

    val capabilityLocations = this.variant.capabilities.map {
        MavenCoords(it.group, it.name, it.version)
    }

    if (!capabilityLocations.isEmpty() && (location == null || !capabilityLocations.contains(location))) {
        location = capabilityLocations[0]
    }

    if (location == null) {
        error("unknown dependency type ${this.variant.owner}")
    }

    val classifierPrefix = "${location.artifact}-${location.version}-"

    if (this.file.name.startsWith(classifierPrefix)) {
        location = MavenCoords(
            location.group!!,
            location.artifact,
            location.version,
            this.file.nameWithoutExtension.substring(classifierPrefix.length),
            this.file.extension
        )
    } else {
        location = MavenCoords(
            location.group!!,
            location.artifact,
            location.version,
            null,
            this.file.extension
        )
    }

    return location
}