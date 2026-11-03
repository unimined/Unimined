package xyz.wagyourtail.unimined.mapping

import xyz.wagyourtail.unimined.api.util.uniminedLogger
import org.gradle.api.GradleException
import org.gradle.api.logging.Logging
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import xyz.wagyourtail.unimined.api.mapping.*
import xyz.wagyourtail.unimined.mapping.element.mapping.MutableClassMapping
import xyz.wagyourtail.unimined.mapping.element.mapping.MutableMethodMapping
import xyz.wagyourtail.unimined.mapping.element.mapping.MutableParameterMapping
import xyz.wagyourtail.unimined.mapping.element.metadata.JavadocElement
import xyz.wagyourtail.unimined.mapping.element.metadata.SignatureElement
import xyz.wagyourtail.unimined.mapping.internal.MappingResolver
import xyz.wagyourtail.unimined.mapping.processors.*

/**
 * [MappingPostProcessorRegistry] + the builtin
 * [InitCompletionPostProcessor]: ordering, in-place semantics, no-op on
 * data without a mutable UML view, and `<init>` cell completion through
 * the ServiceLoader default.
 */
class PostProcessorTest {

    private val ctx = MappingResolver.standaloneContext(
        "test-env",
        uniminedLogger(Logging.getLogger(PostProcessorTest::class.java))
    )

    /** UML data with an `<init>` row in `official` only. */
    private fun umlDataWithInit(): UmlMappingData = UmlMappingData(
        mappingTree("official", "named") {
            clazz("a/A", "b/B") {
                method("<init>;(La/A;)V")
            }
        }
    )

    /** Data exposing no mapping-tree view — processors must no-op. */
    private fun portableOnlyData(): MappingData =
        ViewMapMappingData(emptyMap())

    @Test
    fun `init completion fills missing namespace cells`() {
        val data = umlDataWithInit()
        InitCompletionPostProcessor().process(data, ctx)

        val named = Namespace("named")
        val tree = data.view<MappingTree>()!!
        val init = tree.classes.single().methods.single()
        assertEquals("<init>", init.names[named]?.value)
        // donor desc remapped through the class mapping, not copied
        assertEquals("(Lb/B;)V", init.descs[named]?.value)
    }

    @Test
    fun `init completion no-ops without uml view`() {
        val data = portableOnlyData()
        val result = InitCompletionPostProcessor().process(data, ctx)
        assertSame(data, result)
    }

    @Test
    fun `registry folds processors in order`() {
        val calls = mutableListOf<String>()
        fun recorder(tag: String) = object : MappingPostProcessor {
            override val name = tag
            override fun process(data: MappingData, ctx: MappingContext): MappingData {
                calls.add(tag); return data
            }
        }

        val registry = MappingPostProcessorRegistry()
        registry.register(recorder("first"))
        registry.register(recorder("second"))

        registry.process(portableOnlyData(), ctx)
        // ServiceLoader defaults run first; user-registered processors follow
        assertEquals(listOf("first", "second"), calls.takeLast(2))
    }

    @Test
    fun `serviceloader default is registered`() {
        val names = MappingPostProcessorRegistry().processors.map { it.name }
        assertTrue("init-completion" in names)
        assertTrue("unobf-completion" in names)
        assertTrue("param-prefix-strip" in names)
        assertTrue("signature-fixes" in names)
        assertTrue("version-check" in names)
        // metadata-overlay needs configured input; registered manually
        assertTrue("metadata-overlay" !in names)
    }

    /**
     * A 3-namespace tree mixing rows the processor must fill (all
     * present names equal) with rows it must leave (names differ).
     */
    private fun umlDataWithSparseCells(): UmlMappingData = UmlMappingData(
        mappingTree("official", "intermediary", "named") {
            // fully mapped — untouched
            clazz("a/A", "i/I", "c/C")
            // un-obfuscated: official == named, intermediary missing -> fill
            clazz("d/D", null, "d/D") {
                // member names agree across present ns -> fill the rest,
                // desc remapped through the class mapping
                method(null, "m;(Li/I;)V") {
                    parameter(0, 0, null, "p")
                }
                // differing member names are real mappings — left alone
                method("o;()V", null, "run;()V")
            }
            // real mapping missing the dev ns — left alone
            clazz("e/E", "j/J")
        }
    )

    @Test
    fun `unobf completion fills cells only where present names agree`() {
        val data = umlDataWithSparseCells()
        UnobfCompletionPostProcessor().process(data, ctx)

        val official = Namespace("official")
        val intermediary = Namespace("intermediary")
        val named = Namespace("named")
        val tree = data.view<MappingTree>()!!
        val classes = tree.classes.toList()

        // fully-mapped class untouched
        assertEquals("i/I", classes[0].names[intermediary]?.value)
        // un-obfuscated class gets the missing cell
        assertEquals("d/D", classes[1].names[intermediary]?.value)
        // real mapping missing named is left alone
        org.junit.jupiter.api.Assertions.assertNull(classes[2].names[named])
    }

    @Test
    fun `unobf completion fills member cells and remaps descs`() {
        val data = umlDataWithSparseCells()
        UnobfCompletionPostProcessor().process(data, ctx)

        val official = Namespace("official")
        val intermediary = Namespace("intermediary")
        val named = Namespace("named")
        val cls = data.view<MappingTree>()!!.classes.toList()[1]

        val methods = cls.methods.toList()
        val m = methods[0]
        // desc remapped per namespace, not copied — the donor's Li/I;
        // class ref maps to La/A; official and Lc/C; named
        assertEquals("m", m.entries[official]?.name?.value)
        assertEquals("(La/A;)V", m.entries[official]?.descriptor?.value)
        assertEquals("m", m.entries[named]?.name?.value)
        assertEquals("(Lc/C;)V", m.entries[named]?.descriptor?.value)
        // params fill name-only cells
        assertEquals("p", m.parameters.single().names[named]?.value)
        // real member mapping left alone
        org.junit.jupiter.api.Assertions.assertNull(
            methods[1].entries[intermediary]
        )
    }

    @Test
    fun `unobf completion no-ops without uml view`() {
        val data = portableOnlyData()
        val result = UnobfCompletionPostProcessor().process(data, ctx)
        assertSame(data, result)
    }

    // ---- param-prefix-strip ----

    /** Tree with a parchment-style `pX` param plus lookalikes. */
    private fun umlDataWithPrefixedParams(): UmlMappingData = UmlMappingData(
        mappingTree("mojmap", "named") {
            clazz("a/A", "b/B") {
                method("m;(I)V") {
                    parameter(0, 0, "pFoo")
                    parameter(1, 1, "p")
                    parameter(2, 2, "plain", "kept")
                }
            }
        }
    )

    @Test
    fun `param prefix strip removes marker and lowercases`() {
        val data = umlDataWithPrefixedParams()
        ParamPrefixStripPostProcessor().process(data, ctx)

        val mojmap = Namespace("mojmap")
        val named = Namespace("named")
        val method = data.view<MappingTree>()!!
            .classes.single().methods.single()
        val params = method.parameters.toList()
        assertEquals("foo", params[0].names[mojmap]?.value)
        // single-char `p` has no uppercase marker char — untouched
        assertEquals("p", params[1].names[mojmap]?.value)
        // no prefix anywhere — untouched in both namespaces
        assertEquals("plain", params[2].names[mojmap]?.value)
        assertEquals("kept", params[2].names[named]?.value)
    }

    @Test
    fun `param prefix strip honors namespace scoping`() {
        val data = umlDataWithPrefixedParams()
        ParamPrefixStripPostProcessor(namespaces = setOf("named")).process(data, ctx)

        val mojmap = Namespace("mojmap")
        val method = data.view<MappingTree>()!!
            .classes.single().methods.single()
        // mojmap outside the scope — still prefixed
        assertEquals("pFoo", method.parameters.first().names[mojmap]?.value)
    }

    @Test
    fun `param prefix strip no-ops without uml view`() {
        val data = portableOnlyData()
        assertSame(data, ParamPrefixStripPostProcessor().process(data, ctx))
    }

    // ---- signature-fixes ----

    private fun umlDataWithClass(): UmlMappingData = UmlMappingData(
        mappingTree("intermediary", "named") {
            clazz("net/minecraft/class_1", "net/minecraft/SomeRecord")
        }
    )

    @Test
    fun `signature fixes stamp elements from the constructor table`() {
        val data = umlDataWithClass()
        SignatureFixesPostProcessor(
            baseNamespace = "intermediary",
            fixes = mapOf(
                "net/minecraft/class_1" to "<T::Ljava/lang/Object;>Ljava/lang/Object;"
            )
        ).process(data, ctx)

        val cls = data.view<MappingTree>()!!.classes.single()
        val signature = cls.metadata.filterIsInstance<SignatureElement>().single()
        assertEquals(
            "<T::Ljava/lang/Object;>Ljava/lang/Object;",
            signature.value.toString()
        )
        assertEquals(Namespace("intermediary"), signature.baseNs)
    }

    @Test
    fun `signature fixes use the constructor table for registered instances`() {
        val data = umlDataWithClass()
        SignatureFixesPostProcessor(
            baseNamespace = "named",
            fixes = mapOf("net/minecraft/SomeRecord" to "Ljava/lang/Object;")
        ).process(data, ctx)

        val cls = data.view<MappingTree>()!!.classes.single()
        assertEquals(1, cls.metadata.filterIsInstance<SignatureElement>().size)
    }

    @Test
    fun `signature fixes never overwrite an existing signature`() {
        val data = umlDataWithClass()
        val cls = data.view<MappingTree>()!!.classes.single()
                as MutableClassMapping
        cls.addMetadata(
            SignatureElement(
                xyz.wagyourtail.unimined.mapping.jvms.four.seven.nine.one.`class`
                    .ClassSignature.unchecked("Ljava/lang/Object;"),
                Namespace("intermediary")
            )
        )
        SignatureFixesPostProcessor(
            baseNamespace = "intermediary",
            fixes = mapOf("net/minecraft/class_1" to "<X:>Ljava/lang/Object;")
        ).process(data, ctx)

        assertEquals(1, cls.metadata.filterIsInstance<SignatureElement>().size)
    }

    @Test
    fun `signature fixes no-op without a fix source`() {
        val data = umlDataWithClass()
        SignatureFixesPostProcessor().process(data, ctx)
        assertTrue(
            data.view<MappingTree>()!!.classes.single()
                .metadata.none { it is SignatureElement }
        )
    }

    // ---- version-check ----

    @Test
    fun `version check passes when expected properties match`() {
        val data = umlDataWithClass().let {
            UmlMappingData(
                it.tree,
                mapOf("channel" to "parchment", "mcVersion" to "1.20.1")
            )
        }
        val result = VersionCheckPostProcessor(
            mapOf("mcVersion" to "1.20.1")
        ).process(data, ctx)
        assertSame(data, result)
    }

    @Test
    fun `version check fails strictly on mismatch`() {
        val data = umlDataWithClass().let {
            UmlMappingData(
                it.tree,
                mapOf("channel" to "parchment", "mcVersion" to "1.19.4")
            )
        }
        assertThrows(GradleException::class.java) {
            VersionCheckPostProcessor(
                mapOf("mcVersion" to "1.20.1")
            ).process(data, ctx)
        }
    }

    @Test
    fun `version check warns leniently on missing view`() {
        val data = umlDataWithClass()
        val result = VersionCheckPostProcessor(
            mapOf("mcVersion" to "1.20.1"), strict = false
        ).process(data, ctx)
        assertSame(data, result)
    }

    @Test
    fun `version check default no-ops`() {
        val data = umlDataWithClass()
        assertSame(data, VersionCheckPostProcessor().process(data, ctx))
    }

    // ---- metadata-overlay ----

    /**
     * Target tree with a bare `mojmap`-named class/method/param; overlay
     * tree carries javadoc on the matching `mojmap` rows.
     */
    private fun overlayFixture(): Pair<UmlMappingData, UmlMappingData> {
        val mojmap = Namespace("mojmap")
        val target = mappingTree("mojmap", "named") {
            clazz("a/A", "b/B") {
                method("m;(I)V", "run;(I)V") {
                    parameter(0, 0, "x")
                }
            }
        }
        var overlayCls: MutableClassMapping? = null
        var overlayMethod: MutableMethodMapping? = null
        var overlayParam: MutableParameterMapping? = null
        val overlay = mappingTree("mojmap") {
            overlayCls = clazz("a/A") {
                overlayMethod = method("m;(I)V") {
                    overlayParam = parameter(0, 0)
                }
            }
        }
        overlayCls!!.addMetadata(JavadocElement("class doc", mojmap))
        overlayMethod!!.addMetadata(JavadocElement("method doc", mojmap))
        overlayParam!!.addMetadata(JavadocElement("param doc", mojmap))
        return UmlMappingData(target) to UmlMappingData(overlay)
    }

    @Test
    fun `metadata overlay merges javadoc onto matching rows`() {
        val (target, overlay) = overlayFixture()
        MetadataOverlayPostProcessor(overlay).process(target, ctx)

        val cls = target.view<MappingTree>()!!.classes.single()
        assertEquals(
            "class doc",
            cls.metadata.filterIsInstance<JavadocElement>().single().value.toString()
        )
        val method = cls.methods.single()
        assertEquals(
            "method doc",
            method.metadata.filterIsInstance<JavadocElement>().single().value.toString()
        )
        assertEquals(
            "param doc",
            method.parameters.single().metadata
                .filterIsInstance<JavadocElement>().single().value.toString()
        )
    }

    @Test
    fun `metadata overlay keeps the first javadoc on a row`() {
        val (target, overlay) = overlayFixture()
        val mojmap = Namespace("mojmap")
        val cls = target.view<MappingTree>()!!.classes.single()
                as MutableClassMapping
        cls.addMetadata(JavadocElement("original", mojmap))
        MetadataOverlayPostProcessor(overlay).process(target, ctx)

        assertEquals(
            "original",
            cls.metadata.filterIsInstance<JavadocElement>().single().value.toString()
        )
    }

    @Test
    fun `metadata overlay skips namespaces absent from the tree`() {
        val (target, _) = overlayFixture()
        val foreign = Namespace("foreign")
        val overlay = mappingTree("foreign") {
            clazz("a/A")
        }
        (overlay.classes.single() as MutableClassMapping)
            .addMetadata(JavadocElement("doc", foreign))

        MetadataOverlayPostProcessor(UmlMappingData(overlay)).process(target, ctx)
        assertTrue(
            target.view<MappingTree>()!!.classes.single()
                .metadata.none { it is JavadocElement }
        )
    }

    @Test
    fun `metadata overlay no-ops without uml view`() {
        val (_, overlay) = overlayFixture()
        val data = portableOnlyData()
        assertSame(data, MetadataOverlayPostProcessor(overlay).process(data, ctx))
    }

}
