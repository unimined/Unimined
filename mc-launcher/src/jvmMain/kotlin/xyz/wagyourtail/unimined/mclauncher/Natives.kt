package xyz.wagyourtail.unimined.mclauncher

import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import kotlin.io.path.createDirectories
import kotlin.io.path.exists

/** Natives classifier selection and jar extraction. */
object Natives {

    /**
     * Maven coordinates (`group:name:version:classifier`) of the native
     * jars an effective [document] needs on [os]. Classifier `${arch}`
     * substitution follows Mojang's `${arch}` convention.
     */
    fun coordinates(document: VersionData, os: OperatingSystem): List<String> =
        document.libraries.mapNotNull { library ->
            val classifier = library.nativeClassifier(os) ?: return@mapNotNull null
            if (!library.appliesTo(os, emptySet())) return@mapNotNull null
            "${library.name}:$classifier"
        }.distinct()

    /**
     * Extract the native jars into [destination], skipping `META-INF`
     * entries and each source library's `extract.exclude` prefixes.
     * First jar wins a filename collision, per launcher semantics.
     */
    fun extract(
        jars: Iterable<Path>,
        destination: Path,
        document: VersionData,
        os: OperatingSystem
    ) {
        val excludesByClassifier = document.libraries.mapNotNull { library ->
            library.nativeClassifier(os)?.let { it to library.extractExclude }
        }.toMap()
        destination.createDirectories()
        for (jar in jars) {
            val fileName = jar.fileName.toString()
            val excludes = excludesByClassifier.entries.firstOrNull { (classifier, _) ->
                fileName.contains(classifier)
            }?.value ?: emptyList()
            ZipFile(jar.toFile()).use { zip ->
                for (entry in zip.entries()) {
                    if (entry.isDirectory) continue
                    val path = entry.name
                    if (path.startsWith("META-INF")) continue
                    if (excludes.any { path.startsWith(it) }) continue
                    val target = destination.resolve(path).normalize()
                    require(target.startsWith(destination)) {
                        "native jar entry escapes destination: $path"
                    }
                    if (target.exists()) continue
                    target.parent?.createDirectories()
                    zip.getInputStream(entry).use { input ->
                        Files.copy(input, target)
                    }
                }
            }
        }
    }

}
