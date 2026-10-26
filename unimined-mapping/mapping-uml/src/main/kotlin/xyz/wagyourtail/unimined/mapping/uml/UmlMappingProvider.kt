package xyz.wagyourtail.unimined.mapping.uml

import kotlinx.coroutines.runBlocking
import okio.buffer
import okio.source
import org.gradle.api.GradleException
import xyz.wagyourtail.unimined.api.mapping.MappingContext
import xyz.wagyourtail.unimined.api.mapping.MappingData
import xyz.wagyourtail.unimined.api.mapping.MappingProvider
import xyz.wagyourtail.unimined.api.mapping.MaterializedMappingSource
import xyz.wagyourtail.unimined.api.mapping.UmlMappingData
import xyz.wagyourtail.unimined.mapping.MutableMappingTree
import xyz.wagyourtail.unimined.mapping.Namespace
import xyz.wagyourtail.unimined.mapping.formats.FormatProvider
import xyz.wagyourtail.unimined.mapping.formats.FormatRegistry
import xyz.wagyourtail.unimined.mapping.sink.emit
import kotlin.io.path.name
import kotlin.io.path.readText
import java.nio.file.Path

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
            readTree(file, hint).emit(tree, nsRenamer = { ns ->
                nsMap[ns.name]?.let { Namespace(it) } ?: ns
            })
        }
        return UmlMappingData(tree)
    }

    private fun detect(path: Path): FormatProvider? = runCatching {
        FormatRegistry.autodetectFormat(
            path.name, path.readText().byteInputStream().source().buffer()
        )
    }.getOrNull()

    private fun readTree(path: Path, hint: FormatProvider?) = runBlocking {
        val content = path.readText()
        val format = hint ?: FormatRegistry.autodetectFormat(
            path.name, content.byteInputStream().source().buffer()
        ) ?: throw GradleException("unrecognized mapping format: $path")
        format.reader.read(content)
    }

}
