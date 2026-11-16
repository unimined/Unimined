package xyz.wagyourtail.unimined.transform.internal

import xyz.wagyourtail.unimined.api.util.writeAtomically
import xyz.wagyourtail.unimined.mapping.MappingTree
import xyz.wagyourtail.unimined.mapping.Namespace
import xyz.wagyourtail.unimined.mapping.element.MetadataElement
import xyz.wagyourtail.unimined.mapping.element.metadata.JavadocElement
import xyz.wagyourtail.unimined.mapping.sink.MappingSink
import xyz.wagyourtail.unimined.mapping.formats.mcp.v6.MCPv6FieldWriter
import xyz.wagyourtail.unimined.mapping.formats.mcp.v6.MCPv6MethodWriter
import xyz.wagyourtail.unimined.mapping.formats.mcp.v6.MCPv6ParamWriter
import xyz.wagyourtail.unimined.mapping.formats.srg.SrgWriter
import xyz.wagyourtail.unimined.mapping.formats.tiny.v2.TinyV2Writer
import xyz.wagyourtail.unimined.mapping.formats.tsrg.TsrgV1Writer
import xyz.wagyourtail.unimined.mapping.sink.emit
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.exists

/**
 * Runtime-consumed mapping exports — one implementation behind every
 * "feed the dev runtime a mappings file" hook: forge's `srg2mcp.srg`/
 * `srg2mcp.jar`/unprotect-jar, cleanroom's `srg2mcp.tsrg`, and the
 * fabric-family loader `mappings/mappings.tiny`.
 *
 * Output paths are content-keyed (`<cacheRoot>/<sha>/fileName`) — two
 * envs with different trees or specs can never collide on a fixed
 * filename, and identical emissions share the artifact.
 */
object RuntimeMappingExports {

    /** Export formats the dev runtimes consume. */
    enum class Format {
        /** Flat SRG — FML `MCP_TO_SRG`/GradleStart `srg.srg-mcp`. */
        SRG,

        /** Flat TSRG v1 — Cleanroom's `FMLDeobfuscatingRemapper`. */
        TSRG_V1,

        /** `methods/fields/params.csv` jar — FG's `srg2mcp.jar`
         *  (`srgToMCPAsMCP`); the `name` header rewrite is applied. */
        MCPV6_CSV_ZIP,

        /** `mappings/mappings.tiny` jar — unprotect + fabric-family
         *  `MappingConfiguration`. */
        TINY_JAR
    }

    /**
     * What to emit. [nsRenamer] relabels emitted columns (unprotect's
     * dst→`named`, fabric's canonical relabels); [renamerKey] is its
     * stable hash proxy since lambdas can't fingerprint.
     */
    class Spec(
        val format: Format,
        /** Source namespace name — `null` → the tree's first. */
        val srcNs: String? = null,
        /** Destination namespace name — `null` → the tree's last. */
        val dstNs: String? = null,
        /** Emit the full tree instead of the `src → dst` column pair
         *  (fabric's loader mappings relabel every column). */
        val emitAll: Boolean = false,
        val nsRenamer: ((Namespace) -> Namespace)? = null,
        /** Stable key covering [nsRenamer]'s behavior. */
        val renamerKey: String? = null,
        /** Artifact file name under the content-keyed dir. */
        val fileName: String,
        /** Caller salt (e.g. environmentId) when [fileName] alone
         *  can't distinguish exports. */
        val salt: String? = null
    )

    /**
     * Emit [tree] per [spec] into [cacheRoot] and return the artifact
     * path. Content-addressed: the sha covers spec + emitted bytes, so
     * stale exports can't survive a mappings or spec change. Writes
     * only when bytes differ so timestamps stay stable.
     */
    fun export(
        tree: MappingTree, spec: Spec, cacheRoot: Path
    ): Path {
        val src = spec.srcNs?.let { Namespace(it) }
            ?: tree.namespaces.first()
        val dst = spec.dstNs?.let { Namespace(it) }
            ?: tree.namespaces.last()
        val renamer = spec.nsRenamer
        val bytes = when (spec.format) {
            Format.SRG -> emitText(tree, src, dst, spec.emitAll) {
                SrgWriter.write(it)
            }
            Format.TSRG_V1 -> emitText(tree, src, dst, spec.emitAll) {
                TsrgV1Writer.write(it)
            }
            Format.TINY_JAR -> zipOf(
                "mappings/mappings.tiny" to emitText(
                    tree, src, dst, spec.emitAll, renamer
                ) { TinyV2Writer.write(it) }
            )
            Format.MCPV6_CSV_ZIP -> mcpCsvZip(tree, src, dst)
        }
        val sha = MessageDigest.getInstance("SHA-1").apply {
            update(spec.format.name.toByteArray())
            update(if (spec.emitAll) byteArrayOf(1) else byteArrayOf(0))
            update((spec.srcNs ?: "").toByteArray())
            update((spec.dstNs ?: "").toByteArray())
            update((spec.renamerKey ?: "").toByteArray())
            update((spec.salt ?: "").toByteArray())
            update(bytes)
        }.digest().joinToString("") { "%02x".format(it) }
        val file = cacheRoot.resolve(sha.substring(0, 16))
            .resolve(spec.fileName)
        if (!file.exists() ||
            !Files.readAllBytes(file).contentEquals(bytes)
        ) {
            Files.createDirectories(file.parent)
            file.writeAtomically { tmp -> Files.write(tmp, bytes) }
        }
        return file
    }

    private fun emitText(
        tree: MappingTree,
        src: Namespace, dst: Namespace,
        emitAll: Boolean = false,
        renamer: ((Namespace) -> Namespace)? = null,
        writer: ((String) -> Unit) -> xyz.wagyourtail.unimined.mapping.sink.MappingSink
    ): ByteArray = buildString {
        // sort=true is load-bearing: output bytes feed the content-keyed
        // cache dir + CC file fingerprints — unsorted tree order differs
        // run-to-run, which rewrote the export to a new sha dir every run
        // and permanently invalidated the configuration-cache entry.
        // javadoc is stripped for the same reason: the parser's parse→render
        // round-trip is lossy on non-link bracket text, so emitted comment
        // bytes can differ per resolution — and runtimes only read names.
        tree.emit(
            noJavadoc(writer(::append)),
            nsFilter = if (emitAll) tree.namespaces.toList()
            else listOf(src, dst),
            sort = true,
            nsRenamer = renamer ?: { it }
        )
    }.toByteArray()

    /** Sink wrapper dropping [JavadocElement] pushes — runtime
     *  exports (loader `mappings.tiny`, srg2mcp, unprotect) carry
     *  names only; comment bytes are dead weight and their
     *  parse/render round-trip is not resolution-stable. */
    private fun noJavadoc(delegate: MappingSink): MappingSink =
        object : MappingSink by delegate {
            override fun push(meta: MetadataElement): MetadataElement? =
                if (meta is JavadocElement) null else delegate.push(meta)
        }

    /** Deterministic zip — fixed-epoch entries in declaration order. */
    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        ZipOutputStream(out.buffered()).use { zip ->
            for ((name, content) in entries) {
                zip.putNextEntry(ZipEntry(name).apply { time = 0L })
                zip.write(content)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    /** 1.x `srgToMCPAsMCP` — comments off ("the reader forge uses now
     *  is too dumb"), `name` header column for FML's CSV parser. */
    private fun mcpCsvZip(
        tree: MappingTree, src: Namespace, dst: Namespace
    ): ByteArray {
        MCPv6FieldWriter.writeComments = false
        MCPv6MethodWriter.writeComments = false
        try {
            val ns = listOf(src, dst)
            fun csv(
                w: ((String) -> Unit) -> xyz.wagyourtail.unimined.mapping.sink.MappingSink
            ) = buildString {
                tree.emit(noJavadoc(w(::append)), nsFilter = ns, sort = true)
            }.replaceFirst("${src.name},${dst.name},", "${src.name},name,")
                .toByteArray()
            return zipOf(
                "methods.csv" to csv { MCPv6MethodWriter.write(it) },
                "fields.csv" to csv { MCPv6FieldWriter.write(it) },
                "params.csv" to csv { MCPv6ParamWriter.write(it) }
            )
        } finally {
            MCPv6FieldWriter.writeComments = true
            MCPv6MethodWriter.writeComments = true
        }
    }
}
