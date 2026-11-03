package xyz.wagyourtail.unimined.mapping

import xyz.wagyourtail.unimined.api.util.uniminedLogger
import kotlinx.coroutines.runBlocking
import org.gradle.api.GradleException
import xyz.wagyourtail.commons.core.logger.Logger
import org.gradle.api.logging.Logging
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import xyz.wagyourtail.unimined.api.mapping.*
import xyz.wagyourtail.unimined.mapping.formats.tiny.v2.TinyV2Reader
import xyz.wagyourtail.unimined.mapping.internal.MappingResolver
import xyz.wagyourtail.unimined.mapping.jvms.four.two.one.InternalName
import xyz.wagyourtail.unimined.mapping.sink.emit
import xyz.wagyourtail.unimined.mapping.sources.FileMappingSourceProvider
import java.nio.file.Files
import java.nio.file.Path

class MappingPipelineTest {

    @TempDir
    lateinit var dir: Path

    private fun objects() = ProjectBuilder.builder()
        .withProjectDir(dir.toFile())
        .build()
        .objects

    private fun tinyFile(name: String, content: String): Path {
        val file = dir.resolve(name)
        Files.writeString(file, content)
        return file
    }

    private val ctx = object : MappingContext {
        override val environmentId = "test"
        override val logger: Logger = uniminedLogger(Logging.getLogger(MappingPipelineTest::class.java))
        override val cacheDir: Path? = null
        override fun resolveMaven(notation: String): Path =
            throw UnsupportedOperationException("no maven resolution in tests")
    }

    private fun umlData(content: String): MappingData = runBlocking {
        UmlMappingData(TinyV2Reader.read(content))
    }

    /** Test provider reading tiny v2 files through real UML machinery. */
    private class TinyTestProvider : MappingProvider {
        override val name = "tiny-test"
        override fun supports(spec: MaterializedMappingSource) = spec.files.isNotEmpty()
        override fun resolve(
            source: MaterializedMappingSource,
            ctx: MappingContext
        ): MappingData {
            val tree = MutableMappingTree()
            source.files.forEach {
                runBlocking { TinyV2Reader.read(it.toFile().readText()) }.emit(tree)
            }
            return UmlMappingData(tree)
        }
    }

    @Test
    fun `file source provider materializes files deterministically`() {
        val second = tinyFile("b.tiny", "")
        val first = tinyFile("a.tiny", "")
        val spec = MappingsSpecImpl(objects())
        spec.file(objects().fileCollection().from(second, first))
        val source = spec.effectiveSources().single()

        val materialized = FileMappingSourceProvider().materialize(source, ctx)
        assertEquals(listOf(first, second), materialized.files)
        assertSame(source, materialized.declaration)
        assertEquals(null, materialized.formatHint)
    }

    @Test
    fun `builtin channel fails clearly until manifest machinery lands`() {
        val spec = MappingsSpecImpl(objects())
        spec.official()
        val source = spec.effectiveSources().single()

        val registry = MappingSourceProviderRegistry()
        val provider = registry.select(source)
        assertEquals("builtin", provider.name)
        val error = assertThrows(IllegalStateException::class.java) {
            provider.materialize(source, ctx)
        }
        assertEquals(true, error.message!!.contains("official"))
    }

    @Test
    fun `composer merges chained sources through UML views`() {
        val first = umlData("tiny\t2\t0\tofficial\tintermediary\nc\ta/A\ti/A\n")
        val second = umlData("tiny\t2\t0\tintermediary\tnamed\nc\ti/A\tn/A\n")

        val data = DefaultMappingComposer().compose(
            xyz.wagyourtail.unimined.api.mapping.MappingComposeRequest(
                listOf(first, second)
            ), ctx
        )

        val tree = data.view<MappingTree>()!!
        assertEquals(
            listOf("official", "intermediary", "named"),
            tree.namespaces.map { it.name }
        )
        val off = tree.namespaces.first { it.name == "official" }
        val named = tree.namespaces.first { it.name == "named" }
        assertEquals(
            "n/A",
            tree.getClass(off, InternalName.unchecked("a/A"))
                ?.names?.get(named)?.value
        )
    }

    @Test
    fun `composer fills missing target names from the parent hop`() {
        // f2 is mapped official -> intermediary but absent from the named
        // doc — it must keep its intermediary name in `named`, not fall
        // back to the official source name
        val first = umlData(
            "tiny\t2\t0\tofficial\tintermediary\n" +
                    "c\ta/A\ti/A\n" +
                    "\tf\tI\tx\tfield_1\n" +
                    "\tf\tI\tz\tfield_2\n"
        )
        val second = umlData(
            "tiny\t2\t0\tintermediary\tnamed\n" +
                    "c\ti/A\tn/A\n" +
                    "\tf\tI\tfield_1\ty\n"
        )

        val data = DefaultMappingComposer().compose(
            xyz.wagyourtail.unimined.api.mapping.MappingComposeRequest(
                listOf(first, second)
            ), ctx
        )

        val tree = data.view<MappingTree>()!!
        val off = tree.namespaces.first { it.name == "official" }
        val named = tree.namespaces.first { it.name == "named" }
        val cls = tree.getClass(off, InternalName.unchecked("a/A"))!!
        val fields = cls.fields.associate {
            it.names[off]!!.value to it.names[named]?.value
        }
        assertEquals(mapOf("x" to "y", "z" to "field_2"), fields)
    }

    @Test
    fun `composer passes through single parts and empty requests`() {
        val part = umlData("tiny\t2\t0\tofficial\tnamed\nc\ta/A\tb/B\n")
        val composer = DefaultMappingComposer()

        assertSame(
            part,
            composer.compose(
                xyz.wagyourtail.unimined.api.mapping.MappingComposeRequest(listOf(part)), ctx
            )
        )
        assertEquals(
            emptySet<Any>(),
            composer.compose(
                xyz.wagyourtail.unimined.api.mapping.MappingComposeRequest(emptyList()), ctx
            ).viewTypes
        )
    }

    @Test
    fun `composer selection accepts tree-view multi-source data`() {
        val spec = MappingsSpecImpl(objects())
        spec.file(tinyFile("a.tiny", ""))
        spec.file(tinyFile("b.tiny", ""))

        val parts = listOf(
            object : MappingData {
                override val viewTypes = setOf(MappingTree::class)
                override fun <T : Any> view(type: Class<T>): T? = null
            },
            object : MappingData {
                override val viewTypes = setOf(MappingTree::class)
                override fun <T : Any> view(type: Class<T>): T? = null
            }
        )
        // parts declare the canonical UML MappingTree view, so the
        // default composer claims the request
        assertEquals(
            "default",
            MappingComposerRegistry().select(
                xyz.wagyourtail.unimined.api.mapping.MappingComposeRequest(parts)
            ).name
        )
    }

    @Test
    fun `compose real forge-yarn sources keeps record accessors distinct`() {
        val cache = Path.of(
            "/run/media/william/m2shared/Projects/unimined-org/unimined/" +
                    "testing/forge-yarn/.gradle/unimined/mapping-cache"
        )
        val files = listOf(
            cache.resolve("builtin/searge/main/searge-1.20.1-20230612.114412-c7d29380ddb3.umf"),
            cache.resolve("intermediary-1.20.1-v2.jar-mappings_mappings.tiny"),
            cache.resolve("yarn-1.20.1+build.10-v2.jar-mappings_mappings.tiny"),
        )
        if (files.any { !Files.exists(it) }) return // dev-box fixture

        // per-source: does the searge umf alone contain the `c` accessor?
        val umfOnly = MappingResolver.readFiles(listOf(files[0]), ctx)
        val umfTree = umfOnly.view<MappingTree>()!!
        val uoff = umfTree.namespaces.first { it.name == "official" }
        println(
            "umf-only methods: " +
                    umfTree.getClass(uoff, InternalName.unchecked("brc"))!!.methods.map {
                        it.entries.entries.joinToString { (ns, e) -> "${ns.name}=${e.name.value}" }
                    }
        )

        // bisect: umf+intermediary, then all three — time each stage
        for ((tag, subset) in listOf("umf+int" to files.take(2), "all" to files)) {
            val t0 = System.nanoTime()
            val partData = MappingResolver.readFiles(subset, ctx)
            System.err.println("[timing] $tag readFiles: ${(System.nanoTime() - t0) / 1_000_000}ms")
            val pTree = partData.view<MappingTree>()!!
            val poff = pTree.namespaces.first { it.name == "official" }
            println(
                "$tag methods: " + pTree.getClass(poff, InternalName.unchecked("brc"))!!
                    .methods.map {
                        it.entries.entries.joinToString { (ns, e) -> "${ns.name}=${e.name.value}" }
                    }
            )
        }

        val data = MappingResolver.readFiles(files, ctx)
        val tree = data.view<MappingTree>()!!
        val off = tree.namespaces.first { it.name == "official" }
        val srg = tree.namespaces.first { it.name == "searge" }
        val named = tree.namespaces.first { it.name == "named" }
        val cls = tree.getClass(off, InternalName.unchecked("brc"))!!
        for (m in cls.methods) {
            println(
                "method: " + m.entries.entries.joinToString { (ns, e) ->
                    "${ns.name}=${e.name.value}${e.descriptor?.let { ";${it.value}" } ?: ""}"
                }
            )
        }
        // hashCode must not gain a named mapping from a desc-ambiguous
        // merge; accessors keep their yarn names
        assertEquals(
            "hashCode",
            cls.methods.single { it.entries[srg]?.name?.value == "hashCode" }
                .entries[named]?.name?.value
        )
        assertEquals(
            "searchDistance",
            cls.methods.single { it.entries[srg]?.name?.value == "f_27328_" }
                .entries[named]?.name?.value
        )
    }

    @Test
    fun `resolver runs the full materialize-resolve-compose pipeline`() {
        val first = tinyFile("inter.tiny", "tiny\t2\t0\tofficial\tintermediary\nc\ta/A\ti/A\n")
        val second = tinyFile("named.tiny", "tiny\t2\t0\tintermediary\tnamed\nc\ti/A\tn/A\n")
        val spec = MappingsSpecImpl(objects())
        // formatHint disambiguates — the service-loaded uml provider
        // declines a hint it can't name, leaving tiny-test the only match
        spec.file(first) { formatHint("tiny-test") }
        spec.file(second) { formatHint("tiny-test") }

        val providers = MappingProviderRegistry().apply { register(TinyTestProvider()) }
        val data = MappingResolver.resolve(spec, ctx, providers = providers)

        assertEquals(
            listOf("official", "intermediary", "named"),
            spec.namespaces
        )
        val tree = data.view<MappingTree>()!!
        val off = tree.namespaces.first { it.name == "official" }
        val named = tree.namespaces.first { it.name == "named" }
        assertEquals(
            "n/A",
            tree.getClass(off, InternalName.unchecked("a/A"))
                ?.names?.get(named)?.value
        )
    }

}
