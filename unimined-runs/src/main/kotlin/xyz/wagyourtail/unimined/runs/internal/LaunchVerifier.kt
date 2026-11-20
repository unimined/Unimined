package xyz.wagyourtail.unimined.runs.internal

import java.io.File
import java.util.jar.JarFile

/**
 * Static audit of a fully-assembled launch plan — catches wiring
 * bugs a successful exec would surface as launch-time failures
 * (`ClassNotFoundException: KnotClient` when the loader never made
 * the prod classpath). Runs inside `run<Name>` when
 * `-Punimined.run.verify` is set; no JVM is forked.
 *
 * Checks:
 * - every classpath entry exists on disk (stale conf wiring)
 * - the resolved main class is present on the classpath
 * - loader-required resources — `mappings/mappings.tiny` when a
 *   `fabric.gameMappingNamespace` sysprop is set (fabric-family
 *   prod/intermediary signal; the loader's `MappingConfiguration`
 *   resolves the runtime namespace through it)
 * - javaagent jars exist
 * - the asset index exists when declared
 */
internal object LaunchVerifier {

    /** One finding; `error = true` fails the task. */
    data class Finding(val error: Boolean, val message: String)

    fun verify(
        mainClass: String?,
        classpath: List<File>,
        jvmArgs: List<String>,
        javaagents: List<File>,
        assetIndex: File?,
    ): List<Finding> = buildList {
        if (classpath.isEmpty()) {
            add(Finding(true, "launch classpath is empty"))
        }
        classpath.filter { !it.exists() }.forEach {
            add(Finding(true, "classpath entry missing on disk: ${it.absolutePath}"))
        }
        if (mainClass.isNullOrBlank()) {
            add(Finding(true, "no main class resolved"))
        } else {
            val entry = mainClass.replace('.', '/') + ".class"
            if (!classpathHas(classpath, entry)) {
                add(Finding(true,
                    "main class '$mainClass' not found on the classpath " +
                            "(${classpath.size} entries)"))
            }
        }
        // fabric-family prod: the runtime-namespace flag requires the
        // relabeled mappings jar — its absence fails at loader init,
        // not as a CNFE, but the check is identical in shape
        if (jvmArgs.any { it.startsWith("-Dfabric.gameMappingNamespace=") } &&
            !classpathHas(classpath, "mappings/mappings.tiny")) {
            add(Finding(true,
                "fabric.gameMappingNamespace is set but no classpath " +
                        "entry carries mappings/mappings.tiny"))
        }
        javaagents.filter { !it.isFile }.forEach {
            add(Finding(true, "javaagent missing on disk: ${it.absolutePath}"))
        }
        if (assetIndex != null && !assetIndex.isFile) {
            add(Finding(true, "asset index missing on disk: ${assetIndex.absolutePath}"))
        }
    }

    /** True when any classpath entry — jar or directory — carries
     *  the slash-separated [entry] path. */
    private fun classpathHas(classpath: List<File>, entry: String): Boolean =
        classpath.any { file ->
            if (file.isDirectory) {
                file.resolve(entry).isFile
            } else if (file.isFile) {
                runCatching {
                    JarFile(file).use { it.getJarEntry(entry) != null }
                }.getOrDefault(false)
            } else false
        }
}
