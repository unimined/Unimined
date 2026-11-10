package xyz.wagyourtail.unimined.remap.internal

import org.gradle.api.GradleException
import xyz.wagyourtail.unimined.mapping.jvms.four.two.one.InternalName
import xyz.wagyourtail.unimined.mapping.MappingTree
import xyz.wagyourtail.unimined.mapping.Namespace
import java.nio.file.Path
import java.util.zip.ZipFile

/**
 * Execution-time namespace inference for standalone artifact remaps: when
 * a caller supplies a UML-readable mapping file but omits `from`/`to`, the
 * namespaces are resolved by sampling the input jar's class names against
 * each namespace column — the column with the most hits is the source
 * namespace. The target is the single remaining column; ambiguity (more
 * than one candidate) fails with an actionable error.
 */
object NamespaceDetect {

    private const val SAMPLE_LIMIT = 400

    /**
     * Resolve (source, target) namespaces for [inputJar] against [tree].
     * Explicitly provided values win; either side may be `null` to request
     * detection of just that side.
     */
    fun detect(
        inputJar: Path,
        tree: MappingTree,
        fns: String?,
        tns: String?
    ): Pair<String, String> {
        if (fns != null && tns != null) return fns to tns

        val inputNames = sampleClassNames(inputJar)
        val source = fns ?: detectSource(inputNames, tree)
        val target = tns ?: detectTarget(tree, source)
        return source to target
    }

    /** Read up to [SAMPLE_LIMIT] class internal names from the jar. */
    fun sampleClassNames(inputJar: Path): List<String> {
        val names = mutableListOf<String>()
        ZipFile(inputJar.toFile()).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements() && names.size < SAMPLE_LIMIT) {
                val name = entries.nextElement().name
                if (!name.endsWith(".class") || name.startsWith("META-INF/")) continue
                val internal = name.removeSuffix(".class")
                if (internal == "module-info" || internal.endsWith("/package-info")) continue
                names += internal
            }
        }
        return names
    }

    private fun detectSource(inputNames: List<String>, tree: MappingTree): String {
        val scores = tree.namespaces.mapNotNull { ns ->
            val hits = inputNames.count {
                tree.getClass(ns, InternalName.unchecked(it)) != null
            }
            if (hits > 0) ns to hits else null
        }.sortedByDescending { it.second }
        val best = scores.firstOrNull()
            ?: throw GradleException(
                "unimined: could not autodetect the source namespace — no " +
                "class in the input jar matches any namespace column of the " +
                "mapping file. Set `from` explicitly on `remap.artifact()`."
            )
        return best.first.name
    }

    private fun detectTarget(tree: MappingTree, source: String): String {
        val candidates = tree.namespaces.filter { it.name != source }
        return when (candidates.size) {
            1 -> candidates.single().name
            0 -> source // single-namespace tree: identity remap
            else -> throw GradleException(
                "unimined: ambiguous target namespace — after source `$source` " +
                "the remaining candidates are " +
                candidates.joinToString(", ") { "`${it.name}`" } +
                ". Set `to` explicitly on `remap.artifact()`."
            )
        }
    }

}
