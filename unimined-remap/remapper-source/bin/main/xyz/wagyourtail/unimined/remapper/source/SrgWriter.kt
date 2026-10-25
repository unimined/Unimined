package xyz.wagyourtail.unimined.remapper.source

import xyz.wagyourtail.unimined.api.mapping.MappingTree
import xyz.wagyourtail.unimined.api.mapping.MappingVisitor
import java.io.PrintWriter
import java.nio.file.Path
import kotlin.io.path.bufferedWriter

/**
 * Emits standard SRG (`PK:`/`CL:`/`FD:`/`MD:`) from a resolved
 * [MappingTree] via the portable [MappingVisitor] interchange — the
 * source-remap tool's input format (as produced by UML's `SrgWriter`
 * in 1.x), without depending on UML internals.
 */
internal object SrgWriter {

    private class Member(
        val srcClass: String,
        val srcName: String,
        val srcDesc: String?,
        val dstName: String
    )

    fun write(tree: MappingTree, source: String, target: String, out: Path) {
        val classes = mutableMapOf<String, String>()
        val packages = mutableMapOf<String, String>()
        val fields = mutableListOf<Member>()
        val methods = mutableListOf<Member>()

        tree.accept(source, target, object : MappingVisitor {
            override fun visitClass(srcName: String, dstName: String) {
                classes[srcName] = dstName
            }
            override fun visitField(srcClass: String, srcName: String, srcDesc: String?, dstName: String) {
                fields += Member(srcClass, srcName, srcDesc, dstName)
            }
            override fun visitMethod(srcClass: String, srcName: String, srcDesc: String?, dstName: String) {
                methods += Member(srcClass, srcName, srcDesc, dstName)
            }
            // SRG cannot express method-arg/var renames — params are
            // handled by the tool's own naming, not the mapping file.
            override fun visitMethodArg(srcClass: String, srcMethod: String, srcDesc: String?, index: Int, dstName: String) {}
            override fun visitMethodVar(srcClass: String, srcMethod: String, srcDesc: String?, lvIndex: Int, dstName: String) {}
            override fun visitPackage(srcName: String, dstName: String) {
                packages[srcName] = dstName
            }
        })

        fun mapClass(internal: String): String =
            classes[internal] ?: run {
                // fall back to the longest matching package rename —
                // covers members of classes only moved by a PK row
                val best = packages.entries
                    .filter { internal == it.key || internal.startsWith("${it.key}/") }
                    .maxByOrNull { it.key.length }
                best?.let { it.value + internal.substring(it.key.length) } ?: internal
            }

        fun remapDesc(desc: String): String =
            desc.replace(Regex("""L([^;]+);""")) { "L${mapClass(it.groupValues[1])};" }

        out.bufferedWriter().use { w ->
            PrintWriter(w).apply {
                packages.forEach { (s, d) -> println("PK: $s $d") }
                classes.forEach { (s, d) -> println("CL: $s $d") }
                fields.forEach {
                    println("FD: ${it.srcClass}/${it.srcName} ${mapClass(it.srcClass)}/${it.dstName}")
                }
                methods.forEach {
                    println("MD: ${it.srcClass}/${it.srcName} ${it.srcDesc} ${mapClass(it.srcClass)}/${it.dstName} ${remapDesc(it.srcDesc ?: "")}")
                }
            }.flush()
        }
    }

}
