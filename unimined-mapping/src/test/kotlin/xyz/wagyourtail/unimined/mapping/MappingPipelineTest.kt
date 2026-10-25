package xyz.wagyourtail.unimined.mapping

import kotlinx.coroutines.runBlocking
import org.gradle.api.GradleException
import org.gradle.api.logging.Logger
import org.gradle.api.logging.Logging
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import xyz.wagyourtail.unimined.api.mapping.MappingContext
import xyz.wagyourtail.unimined.api.mapping.MappingData
import xyz.wagyourtail.unimined.api.mapping.MappingProvider
import xyz.wagyourtail.unimined.api.mapping.MappingTree
import xyz.wagyourtail.unimined.api.mapping.MappingVisitor
import xyz.wagyourtail.unimined.api.mapping.MaterializedMappingSource
import xyz.wagyourtail.unimined.api.mapping.UmlMappingData
import xyz.wagyourtail.unimined.api.mapping.view
import xyz.wagyourtail.unimined.api.spi.SpiSelectionException
import xyz.wagyourtail.unimined.mapping.MutableMappingTree
import xyz.wagyourtail.unimined.mapping.formats.tiny.v2.TinyV2Reader
import xyz.wagyourtail.unimined.mapping.internal.MappingResolver
import xyz.wagyourtail.unimined.mapping.sink.emit
import xyz.wagyourtail.unimined.mapping.sources.BuiltinMappingSourceProvider
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
        override val logger: Logger = Logging.getLogger(MappingPipelineTest::class.java)
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
        val error = assertThrows(GradleException::class.java) {
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
        assertEquals(listOf("official", "intermediary", "named"), tree.namespaces)
        val mapped = mutableListOf<String>()
        tree.accept("official", "named", object : MappingVisitor {
            override fun visitClass(srcName: String, dstName: String) {
                mapped += "$srcName->$dstName"
            }
        })
        assertEquals(listOf("a/A->n/A"), mapped)
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
    fun `composer selection rejects portable-only multi-source data`() {
        val spec = MappingsSpecImpl(objects())
        spec.file(tinyFile("a.tiny", ""))
        spec.file(tinyFile("b.tiny", ""))

        val parts = listOf(
            object : MappingData {
                override val viewTypes = setOf(MappingTree::class)
                override fun <T : xyz.wagyourtail.unimined.api.mapping.MappingDataView> view(
                    type: Class<T>
                ): T? = null
            },
            object : MappingData {
                override val viewTypes = setOf(MappingTree::class)
                override fun <T : xyz.wagyourtail.unimined.api.mapping.MappingDataView> view(
                    type: Class<T>
                ): T? = null
            }
        )
        assertThrows(SpiSelectionException::class.java) {
            MappingComposerRegistry().select(
                xyz.wagyourtail.unimined.api.mapping.MappingComposeRequest(parts)
            )
        }
    }

    @Test
    fun `resolver runs the full materialize-resolve-compose pipeline`() {
        val first = tinyFile("inter.tiny", "tiny\t2\t0\tofficial\tintermediary\nc\ta/A\ti/A\n")
        val second = tinyFile("named.tiny", "tiny\t2\t0\tintermediary\tnamed\nc\ti/A\tn/A\n")
        val spec = MappingsSpecImpl(objects())
        spec.file(first)
        spec.file(second)

        val providers = MappingProviderRegistry().apply { register(TinyTestProvider()) }
        val data = MappingResolver.resolve(spec, ctx, providers = providers)

        assertEquals(listOf("official", "intermediary", "named"), spec.namespaces)
        val mapped = mutableListOf<String>()
        data.view<MappingTree>()!!.accept("official", "named", object : MappingVisitor {
            override fun visitClass(srcName: String, dstName: String) {
                mapped += "$srcName->$dstName"
            }
        })
        assertEquals(listOf("a/A->n/A"), mapped)
    }

}
