package xyz.wagyourtail.unimined.remap.spi

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import xyz.wagyourtail.commons.core.logger.JavaLogWrapper
import xyz.wagyourtail.commons.core.logger.Logger
import xyz.wagyourtail.unimined.api.mapping.MappingData
import xyz.wagyourtail.unimined.api.mapping.ViewMapMappingData
import xyz.wagyourtail.unimined.api.spec.RemapSpec
import xyz.wagyourtail.unimined.mapping.MappingTree
import xyz.wagyourtail.unimined.mapping.MutableMappingTree
import xyz.wagyourtail.unimined.mapping.Namespace
import xyz.wagyourtail.unimined.mapping.jvms.four.two.one.InternalName
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.io.path.outputStream

/**
 * The [addExtraInnerClassMappings] snapshot-set overload — the shape
 * in-place patchers (the `jar-processors` step's capability hook)
 * consume. Covers the overlay swap onto a [MutableStepContext], the
 * shared base tree staying pristine, `this_class` keying when the zip
 * entry path disagrees, non-inner additions producing no rows, and the
 * pre-existing-row skip.
 *
 * Class bytes are hand-rolled minimal constant pools — the only consumer
 * inside the helper is the constant-pool walk extracting `this_class`,
 * so a two-entry pool (Utf8 name + Class ref) plus the header is the
 * honest fixture shape.
 */
class ExtraInnerClassMappingsOverloadTest {

    @TempDir
    lateinit var dir: Path

    private val official = Namespace("official")
    private val named = Namespace("named")

    private class Ctx(
        tree: MutableMappingTree?,
        override val workDir: Path
    ) : MutableStepContext {
        override val surface = RemapSurface.JAR
        override val state: PipelineState by lazy {
            PipelineState(spec.sourceNamespace, null)
        }
        override val spec = object : RemapSpec {
            override val sourceNamespace = "official"
            override val targetNamespace = "named"
            override val classpath: List<Path> = emptyList()
        }
        override var mappings: MappingData? =
            tree?.let { ViewMapMappingData(mapOf(MappingTree::class to it)) }
        override val classpath: List<Path> = emptyList()
        override val logger: Logger =
            JavaLogWrapper(java.util.logging.Logger.getLogger("eicm-overload-test"))
    }

    /** minimal class bytes whose constant pool declares [thisClass] —
     *  magic + version + two-entry pool + access/this header, exactly
     *  what the helper's constant-pool walk reads. */
    private fun classBytes(thisClass: String): ByteArray {
        val name = thisClass.toByteArray(Charsets.UTF_8)
        val bos = ByteArrayOutputStream()
        val w = DataOutputStream(bos)
        w.writeInt(0xCAFEBABE.toInt())
        w.writeShort(0) // minor version
        w.writeShort(52) // major version
        w.writeShort(3) // constant_pool_count (entries #1, #2)
        w.writeByte(1) // #1 CONSTANT_Utf8
        w.writeShort(name.size)
        w.write(name)
        w.writeByte(7) // #2 CONSTANT_Class → #1
        w.writeShort(1)
        w.writeShort(0x0021) // access_flags
        w.writeShort(2) // this_class → #2
        w.writeShort(0) // super_class (none — never read)
        w.flush()
        return bos.toByteArray()
    }

    private fun jar(name: String, entries: Map<String, ByteArray>): Path {
        val out = dir.resolve(name)
        JarOutputStream(out.outputStream()).use { jos ->
            for ((n, b) in entries) {
                jos.putNextEntry(JarEntry(n)); jos.write(b); jos.closeEntry()
            }
        }
        return out
    }

    private fun tree(vararg pairs: Pair<String, String>): MutableMappingTree =
        MutableMappingTree(setOf(official, named)).apply {
            for ((src, dst) in pairs) {
                clazz(mapOf(
                    official to InternalName.unchecked(src),
                    named to InternalName.unchecked(dst)))
            }
        }

    private fun treeOf(data: MappingData?): MappingTree? =
        data?.view(MappingTree::class.java)

    @Test
    fun `added inner classes get overlay rows renested from the mapped outer`() {
        val base = tree("added/New" to "com/example/Added")
        val ctx = Ctx(base, dir)
        val post = jar("post.jar", mapOf(
            "a/A.class" to classBytes("a/A"),
            "added/New\$Inner.class" to classBytes("added/New\$Inner")))
        addExtraInnerClassMappings(setOf("a/A"), post, ctx)

        // the swap published a private overlay onto the context
        val overlaid = treeOf(ctx.mappings)
        assertNotNull(overlaid)
        val row = overlaid!!.getClass(official,
            InternalName.unchecked("added/New\$Inner"))
        assertNotNull(row, "patched-in inner class must carry a row")
        assertEquals("added/New\$Inner", row!!.names[official]?.value,
            "the stub is keyed in the source namespace")
        assertEquals("com/example/Added\$Inner", row.names[named]?.value,
            "renest derives the named name from the mapped outer")

        // the shared base tree is never mutated — the rows live in the
        // pipeline-private overlay only
        assertNull(base.getClass(official,
            InternalName.unchecked("added/New\$Inner")),
            "the daemon-shared base tree must stay pristine")
    }

    @Test
    fun `row keys on this_class not the zip entry path`() {
        // the FG2 ain\$22 pitfall: patch records emit obf internals
        // inside srg-path entries — entry-path keying would land the
        // stub under the wrong column
        val base = tree("ain" to "net/minecraft/item/Item")
        val ctx = Ctx(base, dir)
        val post = jar("post.jar", mapOf(
            "net/minecraft/item/Item\$22.class" to classBytes("ain\$22")))
        addExtraInnerClassMappings(emptySet(), post, ctx)

        val overlaid = treeOf(ctx.mappings)!!
        val row = overlaid.getClass(official, InternalName.unchecked("ain\$22"))
        assertNotNull(row, "the row must key on the this_class internal name")
        assertEquals("net/minecraft/item/Item\$22", row!!.names[named]?.value)
        assertNull(overlaid.getClass(official,
            InternalName.unchecked("net/minecraft/item/Item\$22")),
            "no row may key on the zip entry path")
    }

    @Test
    fun `non-inner additions and pre-existing entries produce no rows`() {
        val base = tree()
        val before: MappingData? = ViewMapMappingData(mapOf(MappingTree::class to base))
        val ctx = Ctx(base, dir)
        ctx.mappings = before
        val post = jar("post.jar", mapOf(
            "a/A.class" to classBytes("a/A"),
            "added/Plain.class" to classBytes("added/Plain")))
        addExtraInnerClassMappings(setOf("a/A"), post, ctx)

        // nothing added with a `$` — the helper returns before even
        // swapping the context mappings
        assertSame(before, ctx.mappings,
            "no inner additions means no overlay swap")
        val view = treeOf(ctx.mappings)!!
        assertNull(view.getClass(official, InternalName.unchecked("added/Plain")),
            "non-inner additions get no stub row")
    }

    @Test
    fun `pre-existing rows survive the augmentation`() {
        // the outer is deliberately unmapped so renest can derive
        // nothing — the row must come through with its authored names
        // untouched (no forked duplicate, no clobbered leaf)
        val base = tree("added/New\$Inner" to "com/example/CustomInner")
        val ctx = Ctx(base, dir)
        val post = jar("post.jar", mapOf(
            "added/New\$Inner.class" to classBytes("added/New\$Inner")))
        addExtraInnerClassMappings(emptySet(), post, ctx)

        val view = treeOf(ctx.mappings)!!
        val row = view.getClass(official,
            InternalName.unchecked("added/New\$Inner"))
        assertNotNull(row)
        assertEquals("com/example/CustomInner", row!!.names[named]?.value,
            "the authored row survives unharmed")
    }

    @Test
    fun `absent mapping data is a silent no-op`() {
        val ctx = Ctx(null, dir)
        val post = jar("post.jar", mapOf(
            "added/New\$Inner.class" to classBytes("added/New\$Inner")))
        // no mappings on the context — must not throw
        addExtraInnerClassMappings(emptySet(), post, ctx)
        assertNull(ctx.mappings)
    }

}
