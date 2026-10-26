package xyz.wagyourtail.unimined.mapping.uml

import org.gradle.api.logging.Logger
import org.gradle.api.logging.Logging
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import xyz.wagyourtail.unimined.api.mapping.MappingContext
import xyz.wagyourtail.unimined.api.mapping.MappingBundle
import xyz.wagyourtail.unimined.api.mapping.MappingSource
import xyz.wagyourtail.unimined.api.mapping.MappingTree
import xyz.wagyourtail.unimined.api.mapping.MappingVisitor
import xyz.wagyourtail.unimined.api.mapping.MaterializedMappingSource
import xyz.wagyourtail.unimined.api.mapping.UmlMappingTreeView
import xyz.wagyourtail.unimined.api.mapping.view
import xyz.wagyourtail.unimined.mapping.task.ProduceMappingBundleTask
import java.nio.file.Files
import java.nio.file.Path

class UmlMappingProviderTest {

    @TempDir
    lateinit var dir: Path

    private fun tinyFile(content: String = "tiny\t2\t0\tofficial\tnamed\nc\ta/A\tb/B\n"): Path {
        val file = Files.createTempFile(dir, "mappings", ".tiny")
        Files.writeString(file, content)
        return file
    }

    private fun materialized(
        files: List<Path>,
        formatHint: String? = null,
        nsMap: Map<String, String> = emptyMap()
    ): MaterializedMappingSource = object : MaterializedMappingSource {
        override val declaration = object : MappingSource {
            override fun toString() = "test-source"
        }
        override val files = files
        override val formatHint = formatHint
        override val nsMap = nsMap
    }

    private val ctx = object : MappingContext {
        override val environmentId = "test"
        override val logger: Logger = Logging.getLogger(UmlMappingProviderTest::class.java)
        override val cacheDir: Path? = null
        override fun resolveMaven(notation: String): Path =
            throw UnsupportedOperationException("no maven resolution in tests")
    }

    @Test
    fun `uml provider exposes engine and portable views`() {
        val data = UmlMappingProvider().resolve(materialized(listOf(tinyFile())), ctx)

        assertNotNull(data.view<UmlMappingTreeView>())
        assertEquals(
            listOf("official", "named"),
            data.view<MappingTree>()!!.namespaces
        )
    }

    @Test
    fun `uml provider supports files by autodetect and honors format hints`() {
        val file = tinyFile()
        assertTrue(UmlMappingProvider().supports(materialized(listOf(file))))
        assertTrue(UmlMappingProvider().supports(materialized(listOf(file), "tinyv2")))
        assertTrue(!UmlMappingProvider().supports(materialized(listOf(file), "made-up-format")))
    }

    @Test
    fun `multi-file materialized sources merge into one tree`() {
        val chained = tinyFile("tiny\t2\t0\tnamed\textra\nc\tb/B\tc/C\n")
        val data = UmlMappingProvider().resolve(
            materialized(listOf(tinyFile(), chained)), ctx
        )

        val tree = data.view<MappingTree>()!!
        assertEquals(listOf("official", "named", "extra"), tree.namespaces)
        val mapped = mutableListOf<String>()
        tree.accept("official", "extra", object : MappingVisitor {
            override fun visitClass(srcName: String, dstName: String) {
                mapped += "$srcName->$dstName"
            }
        })
        assertEquals(listOf("a/A->c/C"), mapped)
    }

    @Test
    fun `nsMap relabels document namespaces while reading`() {
        // the document calls its target 'named'; the chain calls it 'mojmap'
        val data = UmlMappingProvider().resolve(
            materialized(listOf(tinyFile()), nsMap = mapOf("named" to "mojmap")), ctx
        )
        val tree = data.view<MappingTree>()!!
        assertEquals(listOf("official", "mojmap"), tree.namespaces)
        val mapped = mutableListOf<String>()
        tree.accept("official", "mojmap", object : MappingVisitor {
            override fun visitClass(srcName: String, dstName: String) {
                mapped += "$srcName->$dstName"
            }
        })
        assertEquals(listOf("a/A->b/B"), mapped)
    }

    @Test
    fun `bundle task uses service-loaded mapping provider`() {
        val project = ProjectBuilder.builder().withProjectDir(dir.toFile()).build()
        val task = project.tasks.register(
            "bundle",
            ProduceMappingBundleTask::class.java
        ).get()
        val output = dir.resolve("bundle.json")

        task.environmentId.set("main")
        task.mappingFiles.from(tinyFile())
        task.sourceNamespace.set("official")
        task.targetNamespace.set("named")
        task.bundleFile.set(output.toFile())
        task.produce()

        val bundle = MappingBundle.read(output)
        assertEquals(listOf("official", "named"), bundle.namespaces)
    }

    @Test
    fun `bundle task composes multiple materialized files`() {
        val project = ProjectBuilder.builder().withProjectDir(dir.toFile()).build()
        val task = project.tasks.register(
            "bundle",
            ProduceMappingBundleTask::class.java
        ).get()
        val output = dir.resolve("bundle.json")

        task.environmentId.set("main")
        task.mappingFiles.from(
            tinyFile(),
            tinyFile("tiny\t2\t0\tnamed\textra\nc\tb/B\tc/C\n")
        )
        task.sourceNamespace.set("official")
        task.targetNamespace.set("extra")
        task.bundleFile.set(output.toFile())
        task.produce()

        val bundle = MappingBundle.read(output)
        assertEquals(listOf("official", "named", "extra"), bundle.namespaces)
    }

}
