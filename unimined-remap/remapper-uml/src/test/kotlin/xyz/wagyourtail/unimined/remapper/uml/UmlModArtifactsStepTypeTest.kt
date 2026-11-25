package xyz.wagyourtail.unimined.remapper.uml

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import xyz.wagyourtail.unimined.mapping.MutableMappingTree
import xyz.wagyourtail.unimined.mapping.Namespace
import xyz.wagyourtail.unimined.mapping.remapper.ExtensionContext
import xyz.wagyourtail.unimined.mapping.remapper.asm.AsmExtension
import xyz.wagyourtail.unimined.mapping.remapper.ext.classtransform.ClassTransformExtension
import xyz.wagyourtail.unimined.mapping.remapper.ext.metadata.KotlinMetadataExtension
import xyz.wagyourtail.unimined.mapping.remapper.ext.mixin.MixinExtension
import xyz.wagyourtail.unimined.mapping.remapper.ext.mixin.MixinTables
import xyz.wagyourtail.unimined.remap.ModArtifactsStepSpec
import xyz.wagyourtail.unimined.remapper.uml.ext.Fixtures
import xyz.wagyourtail.unimined.remapper.uml.ext.LiteloaderObfTableExtension
import java.nio.file.Path
import java.util.jar.Manifest

/** Spec→engine negotiation for `modArtifacts`: which
 *  [AsmExtension]s a [ModArtifactsStepSpec]'s fields produce for a
 *  given input jar, and that the refmap/table options reach the
 *  extension constructors. The remap run itself is covered
 *  elsewhere; this pins the flag plumbing. */
class UmlModArtifactsStepTypeTest {

    @TempDir
    lateinit var dir: Path

    private val type = UmlModArtifactsStepType()

    /** Instantiation context — an empty tree is enough; these
     *  assertions read constructor state, not remap behavior. */
    private val ctx = ExtensionContext(
        MutableMappingTree(setOf(
            Namespace("intermediary"), Namespace("named"))),
        Namespace("intermediary"), Namespace("named"))

    private fun jar(
        name: String,
        entries: Map<String, ByteArray> = emptyMap(),
        manifest: Manifest? = null
    ): Path {
        val out = dir.resolve(name)
        Fixtures.writeJar(out, entries, manifest)
        return out
    }

    private fun extensions(input: Path, spec: ModArtifactsStepSpec) =
        type.modArtifactExtensions(input, spec).map { it.create(ctx) }

    /** Read a ctor-carried private field — the extensions keep their
     *  options in ctor fields, and no getter exposes them. */
    private fun Any.field(name: String): Any? =
        javaClass.getDeclaredField(name)
            .also { it.isAccessible = true }.get(this)

    private fun mixinJar(name: String = "mod.jar") = jar(name,
        mapOf("mymod.mixins.json" to "{}".toByteArray()))

    // ---------- gates ----------

    @Test
    fun `empty jar gets only the kotlin metadata pass`() {
        val exts = extensions(jar("empty.jar"), ModArtifactsStepSpec())
        assertEquals(1, exts.size)
        assertTrue(exts.single() is KotlinMetadataExtension)
    }

    @Test
    fun `mixin config in the jar adds the mixin extension`() {
        val exts = extensions(mixinJar(), ModArtifactsStepSpec())
        assertEquals(3, exts.size)
        val mixin = exts.filterIsInstance<MixinExtension>().single()
        // auto-detect: every table, refmap emission on, no suppressions
        assertSame(MixinTables.ALL, mixin.field("table"))
        assertEquals(true, mixin.field("emitRefmap"))
        assertEquals(emptySet<String>(), mixin.field("noRefmap"))
    }

    @Test
    fun `off drops the mixin machinery regardless of jar resources`() {
        // deliberately resource-rich: mixin config, JMA manifest
        // attr, and the liteloader marker — off() must still win
        val manifest = Manifest().apply {
            mainAttributes.putValue("Manifest-Version", "1.0")
            mainAttributes.putValue("JarModAgent-Refmaps",
                "jma.refmap.json")
        }
        val input = jar("rich.jar",
            mapOf(
                "mymod.mixins.json" to "{}".toByteArray(),
                "litemod.json" to "{}".toByteArray(),
                "com/mumfrey/liteloader/core/runtime/Obf.class" to
                        Fixtures.classBytes(
                            "com/mumfrey/liteloader/core/runtime/Obf")
            ),
            manifest)
        val exts = extensions(input,
            ModArtifactsStepSpec().apply { off() })
        // kotlin.Metadata isn't mixin-scoped — it stays
        assertEquals(1, exts.size)
        assertTrue(exts.single() is KotlinMetadataExtension)
    }

    // ---------- refmap options ----------

    @Test
    fun `disableRefmap flags reach the mixin extension`() {
        val spec = ModArtifactsStepSpec().apply {
            disableRefmap()
            disableRefmap("a.mixins.json", "b.mixins.json")
        }
        val mixin = extensions(mixinJar(), spec)
            .filterIsInstance<MixinExtension>().single()
        assertEquals(false, mixin.field("emitRefmap"))
        assertEquals(setOf("a.mixins.json", "b.mixins.json"),
            mixin.field("noRefmap"))
    }

    // ---------- forced tables ----------

    @Test
    fun `forced mixin tables bypass the resource gate`() {
        val empty = jar("empty.jar")
        val base = extensions(empty,
            ModArtifactsStepSpec().apply { enableBaseMixin() })
            .filterIsInstance<MixinExtension>().single()
        assertSame(MixinTables.STANDARD, base.field("table"))

        val extras = extensions(empty,
            ModArtifactsStepSpec().apply { enableMixinExtra() })
            .filterIsInstance<MixinExtension>().single()
        assertSame(MixinTables.EXTRAS, extras.field("table"))

        val both = extensions(empty,
            ModArtifactsStepSpec().apply {
                enableBaseMixin(); enableMixinExtra()
            }).filterIsInstance<MixinExtension>().single()
        assertSame(MixinTables.ALL, both.field("table"))
    }

    @Test
    fun `jma force adds classtransform with the default refmap path`() {
        val exts = extensions(jar("empty.jar"),
            ModArtifactsStepSpec().apply {
                enableJarModAgent()
                disableRefmap()
            })
        val ct = exts.filterIsInstance<ClassTransformExtension>()
            .single()
        // no manifest path declared — the emitted refmap lands on the
        // conventional jarmodagent.refmap.json
        assertEquals("jarmodagent.refmap.json", ct.field("refmapPath"))
        assertEquals(false, ct.field("emitRefmap"))
    }

    @Test
    fun `manifest JarModAgent-Refmaps enables classtransform`() {
        val manifest = Manifest().apply {
            mainAttributes.putValue("Manifest-Version", "1.0")
            // the agent splits on single spaces — the first declared
            // path carries the emitted refmap
            mainAttributes.putValue("JarModAgent-Refmaps",
                "first.refmap.json second.refmap.json")
        }
        val exts = extensions(jar("jma.jar", manifest = manifest),
            ModArtifactsStepSpec())
        val ct = exts.filterIsInstance<ClassTransformExtension>()
            .single()
        assertEquals("first.refmap.json", ct.field("refmapPath"))
        assertEquals(true, ct.field("emitRefmap"))
    }

    // ---------- liteloader ----------

    @Test
    fun `liteloader markers add the obf-table extension`() {
        for (input in listOf(
            jar("obf.jar", mapOf(
                "com/mumfrey/liteloader/core/runtime/Obf.class" to
                        Fixtures.classBytes(
                            "com/mumfrey/liteloader/core/runtime/Obf"))),
            jar("litemod.jar",
                mapOf("litemod.json" to "{}".toByteArray())))) {
            val exts = extensions(input, ModArtifactsStepSpec())
            assertTrue(exts.any { it is LiteloaderObfTableExtension },
                "no liteloader extension for $input")
        }
    }

}
