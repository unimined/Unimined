package xyz.wagyourtail.unimined.transform.processor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import xyz.wagyourtail.commons.core.logger.JavaLogWrapper
import xyz.wagyourtail.commons.core.logger.Logger
import xyz.wagyourtail.unimined.api.mapping.MappingContext
import xyz.wagyourtail.unimined.api.mapping.MappingData
import xyz.wagyourtail.unimined.api.mapping.ViewMapMappingData
import xyz.wagyourtail.unimined.mapping.MappingTree
import xyz.wagyourtail.unimined.mapping.MutableMappingTree
import xyz.wagyourtail.unimined.mapping.Namespace
import java.nio.file.Path

/**
 * [JarProcessorsMappingsPostProcessor] — the spec-scoped mapping
 * half of the `jar-processors` step — over fake processors. Covers
 * the `processMappings()=null` skip, the `buildSpec()=null` skip,
 * declaration-order transform chaining, the lazy spec-context
 * namespace binding, and `mods()`/`mappingContext` passthrough.
 */
class JarProcessorsMappingsPostProcessorTest {

    @TempDir
    lateinit var dir: Path

    private val mctx = object : MappingContext {
        override val environmentId = "main"
        override val logger: Logger = JavaLogWrapper(
            java.util.logging.Logger.getLogger("jppm-test"))
        override val cacheDir: Path? = null
        override fun resolveMaven(notation: String): Path =
            error("no maven in tests")
    }

    private fun specCtx(
        mods: List<JarProcessor.ModJarInfo> = emptyList()
    ) = object : JarProcessor.SpecContext {
        override val productionNamespace = "official"
        override fun mods() = mods
    }

    private fun processor(
        name: String,
        spec: JarProcessor.JarProcessorSpec?,
        transform: ((MappingData, JarProcessor.JarProcessorSpec,
                    JarProcessor.ProcessorContext) -> MappingData)? = null,
        built: MutableList<JarProcessor.SpecContext>? = null,
    ) = object : JarProcessor {
        override val name = name
        override fun buildSpec(context: JarProcessor.SpecContext) =
            spec.also { built?.add(context) }
        override fun serializeSpec(spec: JarProcessor.JarProcessorSpec) = ""
        override fun deserializeSpec(payload: String) =
            object : JarProcessor.JarProcessorSpec {}
        override fun processJar(
            jar: Path, spec: JarProcessor.JarProcessorSpec,
            context: JarProcessor.ProcessorContext
        ) {}
        override fun processMappings() = transform?.let { t ->
            JarProcessor.MappingsProcessor { data, s, ctx -> t(data, s, ctx) }
        }
    }

    private object MarkerSpec : JarProcessor.JarProcessorSpec {
        private const val serialVersionUID = 1L
    }

    private val data: MappingData = ViewMapMappingData(emptyMap())

    @Test
    fun `no mapping-side processors returns data untouched`() {
        val pp = JarProcessorsMappingsPostProcessor(
            { listOf(processor("a", MarkerSpec)) },
            { specCtx() })
        assertSame(data, pp.process(data, mctx))
    }

    @Test
    fun `transforms apply in declaration order with built specs`() {
        val order = mutableListOf<String>()
        fun tag(t: String) =
            { d: MappingData, _: JarProcessor.JarProcessorSpec,
              _: JarProcessor.ProcessorContext ->
                order += t
                ViewMapMappingData(emptyMap(),
                    mapOf("tag" to t))
            }
        val pp = JarProcessorsMappingsPostProcessor(
            { listOf(
                processor("first", MarkerSpec, tag("first")),
                processor("second", MarkerSpec, tag("second"))) },
            { specCtx() })
        val out = pp.process(data, mctx)
        assertEquals(listOf("first", "second"), order)
        // the second transform's data is the first's output
        assertEquals("second", out.properties["tag"])
    }

    @Test
    fun `buildSpec null skips the processor without calling transform`() {
        var transformed = false
        val pp = JarProcessorsMappingsPostProcessor(
            { listOf(
                processor("skip", null,
                    { d, _, _ -> transformed = true; d }),
                processor("run", MarkerSpec,
                    { d, s, _ ->
                        assertSame(MarkerSpec, s)
                        ViewMapMappingData(emptyMap()) })) },
            { specCtx() })
        pp.process(data, mctx)
        assertTrue(!transformed,
            "buildSpec=null must skip transform entirely")
    }

    @Test
    fun `specContext receives the view first namespace name`() {
        var boundNs: String? = "unset"
        val tree = MutableMappingTree(
            setOf(Namespace("intermediary"), Namespace("named")))
        val withTree: MappingData = ViewMapMappingData(
            mapOf(MappingTree::class to tree))
        val pp = JarProcessorsMappingsPostProcessor(
            { listOf(processor("p", MarkerSpec,
                { d, _, _ -> d })) },
            { ns -> boundNs = ns; specCtx() })
        pp.process(withTree, mctx)
        assertEquals("intermediary", boundNs)

        // no tree view → null namespace binding
        boundNs = "unset"
        pp.process(data, mctx)
        assertEquals(null, boundNs)
    }

    @Test
    fun `processor context exposes mods and the mapping context`() {
        val modInfo = object : JarProcessor.ModJarInfo {
            override val jar: Path = dir.resolve("m.jar")
            override val id = "m"
            override fun custom(key: String): String? = null
            override fun readEntry(path: String): ByteArray? = null
        }
        var ctxSeen: JarProcessor.ProcessorContext? = null
        val pp = JarProcessorsMappingsPostProcessor(
            { listOf(processor("p", MarkerSpec,
                { d, _, c -> ctxSeen = c; d })) },
            { object : JarProcessor.SpecContext {
                override val productionNamespace = "official"
                override fun mods() = listOf(modInfo)
            } })
        pp.process(data, mctx)
        assertSame(mctx, ctxSeen!!.mappingContext)
        assertEquals(listOf(modInfo), ctxSeen!!.mods())
        // the live data view flows through context.mappings
        assertSame(data, ctxSeen!!.mappings)
    }

}
