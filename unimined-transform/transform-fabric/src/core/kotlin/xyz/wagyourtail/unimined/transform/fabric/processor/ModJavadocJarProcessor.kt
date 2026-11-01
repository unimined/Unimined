package xyz.wagyourtail.unimined.transform.fabric.processor

import kotlinx.coroutines.runBlocking
import okio.buffer
import okio.source
import xyz.wagyourtail.commonskt.utils.toHex
import xyz.wagyourtail.unimined.api.mapping.UmlMappingData
import xyz.wagyourtail.unimined.mapping.formats.FormatRegistry
import xyz.wagyourtail.unimined.mapping.processors.MetadataOverlayPostProcessor
import xyz.wagyourtail.unimined.transform.fabric.internal.FabricModAnalysis
import xyz.wagyourtail.unimined.transform.processor.JarProcessor
import java.io.Serializable
import java.nio.file.Path
import java.security.MessageDigest

/**
 * Mod-provided javadoc — Loom `ModJavadocProcessor` parity: mod jars
 * declaring `custom."loom:provided_javadoc"` ship a mappings-format
 * file whose comments merge into the environment's composed mappings
 * (the mapping bundle, generated sources, and decompiler javadocs all
 * see them).
 *
 * The jar half is a no-op like Loom's — only the mapping mutation
 * matters. Spec payloads carry each declaration's
 * `jar\tentry\tsha256` so a mod's javadoc content participates in the
 * processed-artifact cache key.
 *
 * Files parse through UML's `FormatRegistry` autodetect (tiny v1/v2 and
 * friends); formats UML has no reader for (e.g. Enigma) log and skip.
 */
class ModJavadocJarProcessor : JarProcessor {

    companion object {
        /** The `fabric.mod.json` `custom` key declaring a javadoc file. */
        const val PROVIDED_JAVADOC_KEY = "loom:provided_javadoc"
    }

    override val name = "mod-provided-javadoc"

    /** One mod's javadoc declaration — jar name + entry + content hash. */
    class Entry(
        val modId: String?,
        val jarName: String,
        val path: String,
        val sha256: String
    ) : Serializable

    /** The active spec — ordered declarations, deterministic by mod id. */
    class Spec(val entries: List<Entry>) : JarProcessor.JarProcessorSpec

    override fun buildSpec(context: JarProcessor.SpecContext): JarProcessor.JarProcessorSpec? {
        val entries = context.mods().mapNotNull { mod ->
            val path = mod.custom(PROVIDED_JAVADOC_KEY) ?: return@mapNotNull null
            val bytes = mod.readEntry(path) ?: return@mapNotNull null
            Entry(
                mod.id, mod.jar.fileName.toString(), path,
                MessageDigest.getInstance("SHA-256")
                    .digest(bytes).toHex()
            )
        }.sortedWith(compareBy({ it.modId }, { it.jarName }))
        return if (entries.isEmpty()) null else Spec(entries)
    }

    override fun serializeSpec(spec: JarProcessor.JarProcessorSpec): String =
        (spec as Spec).entries.joinToString("\n") {
            "${it.jarName}\t${it.path}\t${it.sha256}\t${it.modId ?: ""}"
        }

    override fun deserializeSpec(payload: String): JarProcessor.JarProcessorSpec = Spec(
        payload.lines().filter { it.isNotBlank() }.map { line ->
            val parts = line.split('\t')
            Entry(parts.getOrNull(3)?.ifEmpty { null }, parts[0], parts[1], parts[2])
        }
    )

    /** Nothing to do for the jar — comments live in the mappings. */
    override fun processJar(
        jar: Path, spec: JarProcessor.JarProcessorSpec,
        context: JarProcessor.ProcessorContext
    ) = Unit

    /**
     * Merge every declaration's comment metadata onto the composed
     * tree — first layer wins, matching `MetadataOverlayPostProcessor`
     * semantics (the mod's javadoc does not overwrite mappings' own).
     */
    override fun processMappings() = JarProcessor.MappingsProcessor { data, spec, context ->
        val mappingContext = context.mappingContext ?: return@MappingsProcessor data
        val mods = context.mods()
        var result = data
        for (entry in (spec as Spec).entries) {
            val mod = mods.firstOrNull { it.jar.fileName.toString() == entry.jarName }
            val bytes = mod?.readEntry(entry.path)
            if (bytes == null) {
                context.logger.warning(
                    "[Unimined/JarProcessor] $name: ${entry.path} declared by " +
                            "${entry.modId ?: entry.jarName} is no longer readable; skipping"
                )
                continue
            }
            val tree = runBlocking {
                FormatRegistry.autodetectFormat(
                    entry.path, bytes.inputStream().source().buffer()
                )?.reader?.read(bytes.decodeToString())
            }
            if (tree == null) {
                context.logger.warning(
                    "[Unimined/JarProcessor] $name: unrecognized javadoc " +
                            "format for ${entry.path} in ${entry.jarName}; skipping"
                )
                continue
            }
            result = MetadataOverlayPostProcessor(UmlMappingData(tree))
                .process(result, mappingContext)
        }
        result
    }

}

/**
 * `fabric.mod.json`-aware [JarProcessor.SpecContext] factory — the
 * loader-installed half that lets processors read `custom` keys.
 */
internal fun fabricSpecContext(
    productionNamespace: String, modPaths: List<Path>
) = object : JarProcessor.SpecContext {
    override val productionNamespace = productionNamespace
    override fun mods() = modPaths.map { FabricModJarInfo(it) }
}

/** fmj-backed [JarProcessor.ModJarInfo]. */
internal class FabricModJarInfo(override val jar: Path) : JarProcessor.ModJarInfo {
    override val id: String? get() = FabricModAnalysis.modId(jar)
    override fun custom(key: String): String? = FabricModAnalysis.customValue(jar, key)
    override fun readEntry(path: String): ByteArray? = FabricModAnalysis.readEntry(jar, path)
}
