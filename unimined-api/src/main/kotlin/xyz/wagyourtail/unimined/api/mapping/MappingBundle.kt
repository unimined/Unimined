package xyz.wagyourtail.unimined.api.mapping

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
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
 * work). Consumers replay it through [accept] or wrap it in
 * [BundleMappingData]; no one re-resolves sources.
 */
@Serializable
class MappingBundle(
    override val namespaces: List<String>,
    val source: String,
    val target: String,
    val rows: List<Row>
) : MappingTree {

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

    override fun accept(sourceNamespace: String, targetNamespace: String, visitor: MappingVisitor) {
        require(sourceNamespace == source && targetNamespace == target) {
            "bundle covers $source -> $target, not $sourceNamespace -> $targetNamespace"
        }
        for (row in rows) {
            when (row.kind) {
                Kind.CLASS -> visitor.visitClass(row.srcName!!, row.dstName!!)
                Kind.FIELD -> visitor.visitField(row.srcClass!!, row.srcName!!, row.srcDesc, row.dstName!!)
                Kind.METHOD -> visitor.visitMethod(row.srcClass!!, row.srcName!!, row.srcDesc, row.dstName!!)
                Kind.METHOD_ARG -> visitor.visitMethodArg(row.srcClass!!, row.srcName!!, row.srcDesc, row.index!!, row.dstName!!)
                Kind.METHOD_VAR -> visitor.visitMethodVar(row.srcClass!!, row.srcName!!, row.srcDesc, row.index!!, row.dstName!!)
                Kind.PACKAGE -> visitor.visitPackage(row.srcName!!, row.dstName!!)
            }
        }
    }

    fun toJson(): String = json.encodeToString(serializer(), this)

    fun write(path: Path) = path.writeText(toJson())

    companion object {

        private val json = Json { ignoreUnknownKeys = true }

        /** Capture a resolved tree's `source -> target` view into a bundle. */
        fun capture(source: String, target: String, tree: MappingTree): MappingBundle {
            val rows = mutableListOf<Row>()
            tree.accept(source, target, object : MappingVisitor {
                override fun visitClass(srcName: String, dstName: String) {
                    rows += Row(Kind.CLASS, srcName = srcName, dstName = dstName)
                }
                override fun visitField(srcClass: String, srcName: String, srcDesc: String?, dstName: String) {
                    rows += Row(Kind.FIELD, srcClass, srcName, srcDesc, dstName)
                }
                override fun visitMethod(srcClass: String, srcName: String, srcDesc: String?, dstName: String) {
                    rows += Row(Kind.METHOD, srcClass, srcName, srcDesc, dstName)
                }
                override fun visitMethodArg(srcClass: String, srcMethod: String, srcDesc: String?, index: Int, dstName: String) {
                    rows += Row(Kind.METHOD_ARG, srcClass, srcMethod, srcDesc, dstName, index)
                }
                override fun visitMethodVar(srcClass: String, srcMethod: String, srcDesc: String?, lvIndex: Int, dstName: String) {
                    rows += Row(Kind.METHOD_VAR, srcClass, srcMethod, srcDesc, dstName, lvIndex)
                }
                override fun visitPackage(srcName: String, dstName: String) {
                    rows += Row(Kind.PACKAGE, srcName = srcName, dstName = dstName)
                }
            })
            return MappingBundle(tree.namespaces, source, target, rows)
        }

        fun fromJson(text: String): MappingBundle = json.decodeFromString(serializer(), text)

        fun read(path: Path): MappingBundle = fromJson(path.readText())

    }

}
