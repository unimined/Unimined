package xyz.wagyourtail.unimined.mapping.uml

import xyz.wagyourtail.unimined.api.util.uniminedLogger
import xyz.wagyourtail.commons.core.logger.Logger
import org.gradle.api.logging.Logging
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import xyz.wagyourtail.unimined.api.mapping.*
import xyz.wagyourtail.unimined.mapping.MappingTree
import xyz.wagyourtail.unimined.mapping.Namespace
import xyz.wagyourtail.unimined.mapping.element.metadata.SignatureElement
import xyz.wagyourtail.unimined.mapping.jvms.four.two.one.InternalName
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
        nsMap: Map<String, String> = emptyMap(),
        attachments: Map<String, Path> = emptyMap()
    ): MaterializedMappingSource = object : MaterializedMappingSource {
        override val declaration = object : MappingSource {
            override fun toString() = "test-source"
        }
        override val files = files
        override val formatHint = formatHint
        override val nsMap = nsMap
        override val attachments = attachments
    }

    private val ctx = object : MappingContext {
        override val environmentId = "test"
        override val logger: Logger = uniminedLogger(Logging.getLogger(UmlMappingProviderTest::class.java))
        override val cacheDir: Path? = null
        override fun resolveMaven(notation: String): Path =
            throw UnsupportedOperationException("no maven resolution in tests")
    }

    @Test
    fun `uml provider exposes engine and portable views`() {
        val data = UmlMappingProvider().resolve(materialized(listOf(tinyFile())), ctx)

        assertEquals(
            listOf("official", "named"),
            data.view<MappingTree>()!!.namespaces.map { it.name }
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
        assertEquals(
            listOf("official", "named", "extra"),
            tree.namespaces.map { it.name }
        )
        val off = tree.namespaces.first { it.name == "official" }
        val extra = tree.namespaces.first { it.name == "extra" }
        assertEquals(
            "c/C",
            tree.getClass(off, InternalName.unchecked("a/A"))
                ?.names?.get(extra)?.value
        )
    }

    @Test
    fun `nsMap relabels document namespaces while reading`() {
        // the document calls its target 'named'; the chain calls it 'mojmap'
        val data = UmlMappingProvider().resolve(
            materialized(listOf(tinyFile()), nsMap = mapOf("named" to "mojmap")), ctx
        )
        val tree = data.view<MappingTree>()!!
        assertEquals(
            listOf("official", "mojmap"),
            tree.namespaces.map { it.name }
        )
        val off = tree.namespaces.first { it.name == "official" }
        val mojmap = tree.namespaces.first { it.name == "mojmap" }
        assertEquals(
            "b/B",
            tree.getClass(off, InternalName.unchecked("a/A"))
                ?.names?.get(mojmap)?.value
        )
    }

    @Test
    fun `record_signatures extras stamp signature metadata at resolve`() {
        // a yarn-v2-style jar: mappings doc + extras/record_signatures.json
        val jar = dir.resolve("yarn-mergedv2.jar")
        java.util.jar.JarOutputStream(Files.newOutputStream(jar)).use { out ->
            out.putNextEntry(java.util.jar.JarEntry("mappings/mappings.tiny"))
            out.write("tiny\t2\t0\tofficial\tnamed\nc\ta/A\tb/B\n".toByteArray())
            out.closeEntry()
            out.putNextEntry(
                java.util.jar.JarEntry("extras/record_signatures.json")
            )
            out.write("""{"b/B":"Ljava/lang/Object;"}""".toByteArray())
            out.closeEntry()
        }

        val contents = xyz.wagyourtail.unimined.mapping.internal.MappingArchive
            .extractAll(jar, dir.resolve("extracted"))!!
        assertEquals(1, contents.documents.size)
        assertTrue("extras/record_signatures.json" in contents.attachments)

        val data = UmlMappingProvider().resolve(
            materialized(
                contents.documents, attachments = contents.attachments
            ), ctx
        )
        val named = Namespace("named")
        val cls = data.view<MappingTree>()!!
            .getClass(named, InternalName.unchecked("b/B"))!!
        val sig = cls.metadata
            .filterIsInstance<SignatureElement>().single()
        assertEquals("Ljava/lang/Object;", sig.value.toString())
        assertEquals(named, sig.baseNs)
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

        val data = SerializedMappingData.readEither(output)
        assertEquals(
            listOf("official", "named"),
            data.view<MappingTree>()!!.namespaces.map { it.name }
        )
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

        val data = SerializedMappingData.readEither(output)
        assertEquals(
            listOf("official", "named", "extra"),
            data.view<MappingTree>()!!.namespaces.map { it.name }
        )
    }

}
