package xyz.wagyourtail.unimined.mapping.uml

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.BufferedSource
import okio.buffer
import okio.source
import org.gradle.api.GradleException
import xyz.wagyourtail.commonskt.reader.StringCharReader
import xyz.wagyourtail.unimined.api.mapping.*
import xyz.wagyourtail.unimined.api.util.Perf
import xyz.wagyourtail.unimined.api.util.ProgressGate
import xyz.wagyourtail.unimined.api.util.uniminedLogger
import xyz.wagyourtail.unimined.mapping.internal.CountingMappingSink
import xyz.wagyourtail.unimined.mapping.MutableMappingTree
import xyz.wagyourtail.unimined.mapping.Namespace
import xyz.wagyourtail.unimined.mapping.formats.FormatProvider
import xyz.wagyourtail.unimined.mapping.formats.FormatReaderSettings
import xyz.wagyourtail.unimined.mapping.formats.FormatRegistry
import xyz.wagyourtail.unimined.mapping.formats.umf.UMFB
import xyz.wagyourtail.unimined.mapping.jvms.four.two.one.InternalName
import xyz.wagyourtail.unimined.mapping.processors.SignatureFixesPostProcessor
import xyz.wagyourtail.unimined.mapping.sink.emit
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.inputStream
import kotlin.io.path.name
import kotlin.io.path.readText

/**
 * UML-backed mapping reader — claims any materialized source whose files
 * UML's [FormatRegistry] can read (or whose `formatHint` names a UML
 * format). Multiple inner files merge into one tree.
 */
class UmlMappingProvider : MappingProvider {

    override val name = "uml"

    // marker so detect()/supports() accept .umfb payloads — the actual
    // read dispatches on magic in readTree, never through this provider
    private val UMFB_PROVIDER by lazy { FormatRegistry.byName["umf"] }

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
        // context formats (the MCP csv readers) mutate searge-keyed rows
        // on an existing tree — they must run after the base documents
        // and see the accumulating tree, not a null context
        val files = source.files.sortedBy {
            (detect(it)?.mustBeAfter?.isNotEmpty() == true).compareTo(false)
        }
        // single-file sources with no namespace renaming can parse
        // straight into the result tree — the staging→emit second pass
        // would be a pure copy into an empty tree (~half the read cost
        // on the forge searge umf: 7.3 s emit for a 7.7 s parse).
        val direct = files.size == 1 && nsMap.isEmpty()
        // a trusted materialization is tree output — its rows are pairwise
        // non-mergeable, so the direct load can skip merge trials. Only on
        // the direct path: multi-file sources merge across file boundaries.
        val tree = if (source.trusted && direct)
            MutableMappingTree.canonical() else MutableMappingTree()
        for (file in files) {
            val staged = Perf.span("mappings.read.parse",
                "env" to ctx.environmentId, "file" to file.name) {
                readTree(file, hint, ctx, tree, if (direct) tree else null, source.trusted)
            }
            if (!direct) {
                Perf.span("mappings.read.emit",
                    "env" to ctx.environmentId, "file" to file.name) {
                    staged.emit(
                        CountingMappingSink(tree, composeGate(file.name, ctx)),
                        nsRenamer = { ns -> nsMap[ns.name]?.let { Namespace(it) } ?: ns }
                    )
                }
            }
        }
        Perf.span("mappings.read.sigfix", "env" to ctx.environmentId) {
            applySignatureFixes(tree, source)
        }
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
                runCatching { value() }.getOrNull()?.let {
                    properties[key] = it
                    properties["${declaration.channel}.$key"] = it
                }
            }
            properties
        }

        is MavenMappingSource -> linkedMapOf("coordinate" to declaration.notation)
        else -> emptyMap()
    }

    // UMFB (binary) payloads carry no FormatRegistry entry — the registry's
    // reader contract is CharReader-based — so derived .umfb files are
    // claimed by magic sniff and read via the binary path in readTree
    private fun isUmfb(src: BufferedSource): Boolean = runCatching {
        val peek = src.peek()
        peek.request(4) && peek.readUtf8(4) == "UMFB"
    }.getOrDefault(false)

    private fun detect(path: Path): FormatProvider? = runCatching {
        path.inputStream().source().buffer().use { src ->
            if (isUmfb(src)) UMFB_PROVIDER
            else FormatRegistry.autodetectFormat(path.name, src)
        }
    }.getOrNull()

    private fun readTree(
        path: Path, hint: FormatProvider?, ctx: MappingContext,
        context: xyz.wagyourtail.unimined.mapping.MappingTree? = null,
        into: MutableMappingTree? = null,
        trusted: Boolean = false
    ) = runBlocking {
        val bytes = Files.readAllBytes(path)
        val binary = bytes.size >= 4 &&
                bytes[0] == 'U'.code.toByte() && bytes[1] == 'M'.code.toByte() &&
                bytes[2] == 'F'.code.toByte() && bytes[3] == 'B'.code.toByte()
        val format = if (binary) null else hint ?: FormatRegistry.autodetectFormat(
            path.name, bytes.inputStream().source().buffer()
        ) ?: throw GradleException("unrecognized mapping format: $path")
        val slog = mlog(ctx)
        slog.lifecycle(
            "reading mappings ${path.name} (${format?.name ?: "umfb"} format)"
        )
        // emit into a counted staging tree — the reader's own emit is the
        // parse's hot loop, so each pushed row is real progress
        val gate = ProgressGate { elapsed, done ->
            slog.info { "${path.name}: parsed $done rows (${elapsed}ms)" }
        }
        (into ?: MutableMappingTree()).also { temp ->
            if (binary) {
                // binary payloads are unchecked by construction — the writer
                // only emits well-formed JVMS values
                UMFB.read(bytes, CountingMappingSink(temp, gate))
            } else {
                format!!.reader.readImpl(
                    StringCharReader(bytes.decodeToString().replace("\r", "")),
                    context,
                    CountingMappingSink(temp, gate),
                    emptyMap(),
                    // machine-written files (derived umf cache documents)
                    // skip JVMS revalidation — the writer already emitted
                    // well-formed types
                    if (trusted) FormatReaderSettings(unchecked = true) or format.reader
                    else format.reader
                )
            }
        }
    }

}
