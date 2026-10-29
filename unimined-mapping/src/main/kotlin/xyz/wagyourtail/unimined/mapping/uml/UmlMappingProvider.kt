package xyz.wagyourtail.unimined.mapping.uml

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.buffer
import okio.source
import org.gradle.api.GradleException
import xyz.wagyourtail.commonskt.reader.StringCharReader
import xyz.wagyourtail.unimined.api.mapping.*
import xyz.wagyourtail.unimined.api.util.ProgressGate
import xyz.wagyourtail.unimined.api.util.uniminedLogger
import xyz.wagyourtail.unimined.mapping.internal.CountingMappingSink
import xyz.wagyourtail.unimined.mapping.MutableMappingTree
import xyz.wagyourtail.unimined.mapping.Namespace
import xyz.wagyourtail.unimined.mapping.formats.FormatProvider
import xyz.wagyourtail.unimined.mapping.formats.FormatRegistry
import xyz.wagyourtail.unimined.mapping.jvms.four.two.one.InternalName
import xyz.wagyourtail.unimined.mapping.processors.SignatureFixesPostProcessor
import xyz.wagyourtail.unimined.mapping.sink.emit
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.io.path.readText

/**
 * UML-backed mapping reader — claims any materialized source whose files
 * UML's [FormatRegistry] can read (or whose `formatHint` names a UML
 * format). Multiple inner files merge into one tree.
 */
class UmlMappingProvider : MappingProvider {

    override val name = "uml"

    override fun supports(spec: MaterializedMappingSource): Boolean =
        spec.formatHint?.let { FormatRegistry.byName[it] != null }
            ?: spec.files.all { detect(it) != null }

    override fun resolve(
        source: MaterializedMappingSource,
        ctx: MappingContext
    ): MappingData {
        val hint = source.formatHint?.let {
            FormatRegistry.byName[it]
                ?: throw GradleException("unknown UML mapping format '$it'")
        }
        val nsMap = source.nsMap
        val tree = MutableMappingTree()
        for (file in source.files) {
            readTree(file, hint, ctx).emit(
                CountingMappingSink(tree, composeGate(file.name, ctx)),
                nsRenamer = { ns -> nsMap[ns.name]?.let { Namespace(it) } ?: ns }
            )
        }
        applySignatureFixes(tree, source)
        return UmlMappingData(tree, declarationMetadata(source))
    }

    /**
     * Progress for the file→tree merge emit — rows pushed into the merged
     * tree after the file's own parse (the second of the two merge passes
     * per source file).
     */
    private fun mlog(ctx: MappingContext) =
        uniminedLogger(ctx.environmentId, ctx.logger).subLogger("mappings")

    private fun composeGate(fileName: String, ctx: MappingContext) =
        ProgressGate { elapsed, done ->
            mlog(ctx).info { "$fileName: merged $done rows…" }
        }

    /**
     * Stamp archive extras (`extras/record_signatures.json`, the yarn
     * v2 signature-fix table) onto the resolved tree as
     * [xyz.wagyourtail.unimined.mapping.element.metadata.SignatureElement]
     * metadata. Keys are dst-side class names, so namespaces are tried
     * last-first; unmatched keys are ignored.
     */
    private fun applySignatureFixes(
        tree: MutableMappingTree,
        source: MaterializedMappingSource
    ) {
        val file = source.attachments["extras/record_signatures.json"] ?: return
        val fixes = Json.parseToJsonElement(file.readText()).jsonObject
            .mapValues { it.value.jsonPrimitive.content }
        var remaining = fixes
        for (ns in tree.namespaces.reversed()) {
            val hit = remaining.filterKeys {
                tree.getClass(ns, InternalName.unchecked(it)) != null
            }
            if (hit.isNotEmpty()) {
                SignatureFixesPostProcessor.applyFixes(tree, ns, hit)
                remaining = remaining - hit.keys
            }
            if (remaining.isEmpty()) break
        }
    }

    /**
     * Surface the declaration's stable version metadata as
     * [MappingData.properties] — channel params for builtins
     * (`parchment`'s `mcVersion`/`version`), the notation for maven
     * sources — so post-processors can run compatibility checks.
     */
    private fun declarationMetadata(
        source: MaterializedMappingSource
    ): Map<String, String> = when (val declaration = source.declaration) {
        is BuiltinMappingSource -> {
            val properties = linkedMapOf("channel" to declaration.channel)
            for ((key, value) in declaration.params) {
                runCatching { value.get() }.getOrNull()?.let {
                    properties[key] = it
                    properties["${declaration.channel}.$key"] = it
                }
            }
            properties
        }

        is MavenMappingSource -> linkedMapOf("coordinate" to declaration.notation)
        else -> emptyMap()
    }

    private fun detect(path: Path): FormatProvider? = runCatching {
        FormatRegistry.autodetectFormat(
            path.name, path.readText().byteInputStream().source().buffer()
        )
    }.getOrNull()

    private fun readTree(path: Path, hint: FormatProvider?, ctx: MappingContext) = runBlocking {
        val content = path.readText()
        val format = hint ?: FormatRegistry.autodetectFormat(
            path.name, content.byteInputStream().source().buffer()
        ) ?: throw GradleException("unrecognized mapping format: $path")
        val slog = mlog(ctx)
        slog.lifecycle(
            "reading mappings ${path.name} (${format.name} format)"
        )
        // emit into a counted staging tree — the reader's own emit is the
        // parse's hot loop, so each pushed row is real progress
        val gate = ProgressGate { elapsed, done ->
            slog.info { "${path.name}: parsed $done rows (${elapsed}ms)" }
        }
        MutableMappingTree().also { temp ->
            format.reader.readImpl(
                StringCharReader(content.replace("\r", "")),
                null,
                CountingMappingSink(temp, gate),
                emptyMap(),
                format.reader
            )
        }
    }

}
