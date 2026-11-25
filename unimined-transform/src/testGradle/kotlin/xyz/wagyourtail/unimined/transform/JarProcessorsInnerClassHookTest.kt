package xyz.wagyourtail.unimined.transform

import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import xyz.wagyourtail.commons.core.logger.Logger
import org.gradle.api.logging.Logging
import xyz.wagyourtail.unimined.api.mapping.MappingData
import xyz.wagyourtail.unimined.api.mapping.ViewMapMappingData
import xyz.wagyourtail.unimined.api.spec.RemapSpec
import xyz.wagyourtail.unimined.mapping.MappingTree
import xyz.wagyourtail.unimined.mapping.MutableMappingTree
import xyz.wagyourtail.unimined.mapping.Namespace
import xyz.wagyourtail.unimined.mapping.jvms.four.two.one.InternalName
import xyz.wagyourtail.unimined.remap.spi.MutableStepContext
import xyz.wagyourtail.unimined.remap.spi.PipelineState
import xyz.wagyourtail.unimined.remap.spi.RemapSurface
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.io.path.outputStream

/**
 * The `jar-processors` step's inner-class capability hook: pre-remap
 * processors opting into `contributesPatchAddedClasses` get their
 * class-entry additions diffed per processor and fed to the
 * `addExtraInnerClassMappings` snapshot overload. Covers the
 * capability gate both ways, the post-remap exclusion, the
 * relocation-only false-positive hazard, per-processor snapshot
 * ordering, and `this_class` keying when the zip entry path disagrees.
 *
 * Observability rides the production swap mechanism: the context is a
 * [MutableStepContext], so the augmentation's delta overlay lands on
 * `ctx.mappings` exactly as it does inside the pipeline runner.
 */
class JarProcessorsInnerClassHookTest {

    @TempDir
    lateinit var dir: Path

    private val official = Namespace("official")
    private val named = Namespace("named")

    private inner class Ctx(
        tree: MutableMappingTree?
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
        override val workDir: Path = dir
        override val logger: Logger = xyz.wagyourtail.unimined.api.util.uniminedLogger(
            Logging.getLogger(JarProcessorsInnerClassHookTest::class.java))
    }

    private fun tree(vararg pairs: Pair<String, String>): MutableMappingTree =
        MutableMappingTree(setOf(official, named)).apply {
            for ((src, dst) in pairs) {
                clazz(mapOf(
                    official to InternalName.unchecked(src),
                    named to InternalName.unchecked(dst)))
            }
        }

    private fun inputJar(vararg entries: String): Path {
        val jar = dir.resolve("input-${counter++}.jar")
        JarOutputStream(jar.outputStream()).use { out ->
            for (e in entries) {
                out.putNextEntry(JarEntry(e))
                // valid constant pools for class entries — negative
                // assertions (relocation) are only meaningful when a
                // hypothetical hook run would have produced a row
                out.write(
                    if (e.endsWith(".class"))
                        xyz.wagyourtail.unimined.transform.processor.PayloadJarProcessor
                            .minimalClassBytes(e.removeSuffix(".class"))
                    else byteArrayOf(1, 2, 3)
                )
                out.closeEntry()
            }
        }
        return jar
    }

    private var counter = 0

    private fun spec(vararg payloads: String): JarProcessorsStepSpec {
        val project = ProjectBuilder.builder().build()
        return project.objects.newInstance(JarProcessorsStepSpec::class.java).apply {
            processorSpecs.set(payloads.toList())
        }
    }

    private fun row(data: MappingData?, srcName: String) =
        data?.view(MappingTree::class.java)
            ?.getClass(official, InternalName.unchecked(srcName))

    @Test
    fun `capable pre-remap processor additions get renested rows`() {
        val base = tree("added/New" to "com/example/Added")
        val ctx = Ctx(base)
        val input = inputJar("a/A.class")
        JarProcessorsStepType()
            .create(spec("test-capable-adder add:added/New\$Inner.class=added/New\$Inner"), ctx)
            .apply(input, ctx)

        val row = row(ctx.mappings, "added/New\$Inner")
        assertNotNull(row, "the capable processor's inner addition must carry a row")
        assertEquals("added/New\$Inner", row!!.names[official]?.value,
            "the stub keys in the source namespace")
        assertEquals("com/example/Added\$Inner", row.names[named]?.value,
            "renest derives the named name from the mapped outer")
        // overlay privacy: the shared base tree is never mutated
        assertNull(base.getClass(official,
            InternalName.unchecked("added/New\$Inner")),
            "rows live in the pipeline-private overlay, not the shared tree")
    }

    @Test
    fun `non-contributing processor additions get no rows`() {
        val ctx = Ctx(tree("added/New" to "com/example/Added"))
        val input = inputJar("a/A.class")
        JarProcessorsStepType()
            .create(spec("test-plain-adder add:added/New\$Inner.class=added/New\$Inner"), ctx)
            .apply(input, ctx)

        assertNull(row(ctx.mappings, "added/New\$Inner"),
            "a processor without the capability must produce no rows")
    }

    @Test
    fun `capable post-remap processor is not hooked`() {
        val ctx = Ctx(tree("added/New" to "com/example/Added"))
        val input = inputJar("a/A.class")
        JarProcessorsStepType()
            .create(spec("test-post-adder add:added/New\$Inner.class=added/New\$Inner"), ctx)
            .apply(input, ctx)

        assertNull(row(ctx.mappings, "added/New\$Inner"),
            "post-remap additions are already dev-namespace — no hook")
    }

    @Test
    fun `relocation-only processor produces no false-positive rows`() {
        // the documented hazard: relocation ADDS entries under new
        // paths, so a naive diff would stub every moved inner class —
        // relocation processors must never opt in, and the capability
        // gate is the only thing keeping these rows out
        val ctx = Ctx(tree("relocated/a/A" to "com/example/RelocatedA"))
        val input = inputJar("a/A\$Inner.class")
        JarProcessorsStepType()
            .create(spec("test-relocator move:a/A\$Inner.class=relocated/a/A\$Inner.class"), ctx)
            .apply(input, ctx)

        assertNull(row(ctx.mappings, "relocated/a/A\$Inner"),
            "moved entries must not produce false-positive rows")
    }

    @Test
    fun `two capable processors each diff their own additions`() {
        val ctx = Ctx(tree(
            "added/A" to "com/example/A",
            "added/B" to "com/example/B"))
        val input = inputJar("a/A.class")
        JarProcessorsStepType()
            .create(spec(
                "test-capable-adder add:added/A\$Inner.class=added/A\$Inner",
                // an incapable processor interleaved — its addition
                // must not leak into either capable processor's diff
                "test-plain-adder add:added/Mid\$Inner.class=added/Mid\$Inner",
                "test-capable-adder-2 add:added/B\$Inner.class=added/B\$Inner"), ctx)
            .apply(input, ctx)

        assertNotNull(row(ctx.mappings, "added/A\$Inner"),
            "first capable processor's addition must carry a row")
        assertNotNull(row(ctx.mappings, "added/B\$Inner"),
            "second capable processor's addition must carry a row")
        assertNull(row(ctx.mappings, "added/Mid\$Inner"),
            "the interleaved incapable processor's addition gets no row")
    }

    @Test
    fun `row keys on this_class when the entry path disagrees`() {
        // FG2 `ain$22` pitfall shape through the hook: the merge-source
        // entry sits at an srg path while the class bytes declare the
        // obf internal
        val ctx = Ctx(tree("ain" to "net/minecraft/item/Item"))
        val input = inputJar("a/A.class")
        JarProcessorsStepType()
            .create(spec("test-capable-adder add:net/minecraft/item/Item\$22.class=ain\$22"), ctx)
            .apply(input, ctx)

        val row = row(ctx.mappings, "ain\$22")
        assertNotNull(row, "the row must key on the this_class internal name")
        assertEquals("net/minecraft/item/Item\$22", row!!.names[named]?.value)
        assertNull(row(ctx.mappings, "net/minecraft/item/Item\$22"),
            "no row may key on the zip entry path")
    }

    @Test
    fun `no mapping data degrades silently`() {
        val ctx = Ctx(null)
        val input = inputJar("a/A.class")
        // must not throw — pipelines without a mapping tree simply
        // skip the augmentation
        JarProcessorsStepType()
            .create(spec("test-capable-adder add:added/New\$Inner.class=added/New\$Inner"), ctx)
            .apply(input, ctx)
        assertNull(ctx.mappings)
    }

}
