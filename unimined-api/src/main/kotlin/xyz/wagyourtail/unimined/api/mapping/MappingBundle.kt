package xyz.wagyourtail.unimined.api.mapping

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import xyz.wagyourtail.unimined.mapping.MappingTree
import xyz.wagyourtail.unimined.mapping.MutableMappingTree
import xyz.wagyourtail.unimined.mapping.Namespace
import xyz.wagyourtail.unimined.mapping.jvms.ext.FieldNameAndDescriptor
import xyz.wagyourtail.unimined.mapping.jvms.ext.MethodNameAndDescriptor
import xyz.wagyourtail.unimined.mapping.jvms.four.two.one.InternalName
import xyz.wagyourtail.unimined.mapping.jvms.four.two.one.PackageName
import xyz.wagyourtail.unimined.mapping.jvms.four.two.two.UnqualifiedName
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Documented interchange form of a resolved [MappingTree] namespace pair:
 * a flat, serializable row list.
 *
 * The mappings core resolves the `mappings { }` spec once and hands this
 * off — as a file or serialized input — to tasks and artifact transforms
 * that cannot hold live mapping objects (configuration cache, isolated
 * work). Consumers rehydrate a UML tree via [toUmlTree] or wrap it in
 * [BundleMappingData]; no one re-resolves sources.
 */
@Serializable
class MappingBundle(
    val namespaces: List<String>,
    val source: String,
    val target: String,
    val rows: List<Row>
) {

    @Serializable
    class Row(
        val kind: Kind,
        /** Owning class (internal name) for member kinds. */
        val srcClass: String? = null,
        val srcName: String? = null,
        val srcDesc: String? = null,
        val dstName: String? = null,
        /** METHOD_ARG: parameter index; METHOD_VAR: local-variable index. */
        val index: Int? = null
    )

    enum class Kind { CLASS, FIELD, METHOD, METHOD_ARG, METHOD_VAR, PACKAGE }

    /**
     * Rebuild a UML tree carrying this bundle's rows — a minimal
     * two-namespace tree (`source` + `target`) inside the declared
     * [namespaces] set, equivalent to what the row interchange captured.
     */
    fun toUmlTree(): MutableMappingTree {
        val nss = namespaces.map { Namespace(it) }
        val src = nss.first { it.name == source }
        val dst = nss.first { it.name == target }
        val tree = MutableMappingTree(nss)
        val classes = HashMap<String, xyz.wagyourtail.unimined.mapping.element.mapping.MutableClassMapping>()

        fun cls(srcName: String) = classes.getOrPut(srcName) {
            tree.clazz(mapOf(src to InternalName.unchecked(srcName)))
        }

        for (row in rows) {
            when (row.kind) {
                Kind.CLASS -> {
                    classes[row.srcName!!] = tree.clazz(
                        mapOf(
                            src to InternalName.unchecked(row.srcName),
                            dst to InternalName.unchecked(row.dstName!!)
                        )
                    )
                }

                Kind.PACKAGE -> {
                    fun pkg(v: String) = if (v.endsWith('/')) v else "$v/"
                    tree.package_(
                        mapOf(
                            src to PackageName.unchecked(pkg(row.srcName!!)),
                            dst to PackageName.unchecked(pkg(row.dstName!!))
                        )
                    )
                }

                Kind.FIELD -> cls(row.srcClass!!).field(
                    mapOf(
                        src to FieldNameAndDescriptor.unchecked(
                            row.srcDesc?.let { "${row.srcName};$it" } ?: row.srcName!!
                        ),
                        dst to FieldNameAndDescriptor.unchecked(row.dstName!!)
                    ))

                Kind.METHOD, Kind.METHOD_ARG, Kind.METHOD_VAR -> {
                    val entries = mutableMapOf(
                        src to MethodNameAndDescriptor.unchecked(
                            row.srcDesc?.let { "${row.srcName};$it" } ?: row.srcName!!
                        )
                    )
                    if (row.kind == Kind.METHOD) {
                        entries[dst] = MethodNameAndDescriptor.unchecked(row.dstName!!)
                    }
                    val method = cls(row.srcClass!!).method(entries)
                    when (row.kind) {
                        Kind.METHOD_ARG -> method.parameter(
                            row.index!!, null,
                            mapOf(dst to UnqualifiedName.unchecked(row.dstName!!))
                        )

                        Kind.METHOD_VAR -> method.localVariable(
                            row.index!!, null,
                            mapOf(dst to UnqualifiedName.unchecked(row.dstName!!))
                        )

                        else -> {}
                    }
                }
            }
        }
        return tree
    }

    fun toJson(): String = json.encodeToString(serializer(), this)

    fun write(path: Path) = path.writeText(toJson())

    companion object {

        private val json = Json { ignoreUnknownKeys = true }

        /**
         * Capture a resolved UML tree's `source -> target` view into a
         * bundle. Member descriptors are captured in source-namespace
         * terms — remapped through the tree when the source namespace
         * doesn't declare one (e.g. yarn only carries intermediary
         * descriptors).
         */
        fun capture(
            source: String,
            target: String,
            tree: MappingTree
        ): MappingBundle {
            val src = tree.namespaces.firstOrNull { it.name == source }
                ?: error("namespace '$source' not in ${tree.namespaces.map { it.name }}")
            val dst = tree.namespaces.firstOrNull { it.name == target }
                ?: error("namespace '$target' not in ${tree.namespaces.map { it.name }}")
            val rows = mutableListOf<Row>()

            for (pkg in tree.packages) {
                val s = pkg.names[src]?.value ?: continue
                val d = pkg.names[dst]?.value ?: continue
                rows += Row(Kind.PACKAGE, srcName = s, dstName = d)
            }

            for (cls in tree.classes) {
                val s = cls.names[src]?.value ?: continue
                val d = cls.names[dst]?.value ?: continue
                rows += Row(Kind.CLASS, srcName = s, dstName = d)

                for (f in cls.fields) {
                    val fs = f.names[src]?.value ?: continue
                    val fd = f.names[dst]?.value ?: continue
                    // source-ns descriptor, remapped when absent
                    val fdesc = f.entries[src]?.descriptor?.toString()
                        ?: f.entries.entries.firstNotNullOfOrNull { (ns, e) ->
                            e.descriptor?.let { tree.map(ns, src, it).toString() }
                        }
                    rows += Row(Kind.FIELD, s, fs, fdesc, fd)
                }

                for (m in cls.methods) {
                    val ms = m.names[src]?.value ?: continue
                    val md = m.names[dst]?.value ?: continue
                    val mdesc = m.descs[src]?.value
                        ?: m.descs.entries.firstOrNull()?.let { (ns, desc) ->
                            tree.map(ns, src, desc).value
                        }
                    rows += Row(Kind.METHOD, s, ms, mdesc, md)
                    for (p in m.parameters) {
                        val pd = p.names[dst]?.value ?: continue
                        val idx = p.index ?: continue
                        rows += Row(Kind.METHOD_ARG, s, ms, mdesc, pd, idx)
                    }
                    for (v in m.localVariables) {
                        val vd = v.names[dst]?.value ?: continue
                        rows += Row(Kind.METHOD_VAR, s, ms, mdesc, vd, v.lvOrd)
                    }
                }
            }
            return MappingBundle(tree.namespaces.map { it.name }, source, target, rows)
        }

        fun fromJson(text: String): MappingBundle = json.decodeFromString(serializer(), text)

        fun read(path: Path): MappingBundle = fromJson(path.readText())

    }

}
