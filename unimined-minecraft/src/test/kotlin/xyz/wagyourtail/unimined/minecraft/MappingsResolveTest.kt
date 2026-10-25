package xyz.wagyourtail.unimined.minecraft

import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import xyz.wagyourtail.unimined.api.mapping.UmlMappingTreeView
import xyz.wagyourtail.unimined.api.mapping.view
import xyz.wagyourtail.unimined.mapping.MappingsSpecImpl
import java.nio.file.Files
import java.nio.file.Path

class MappingsResolveTest {

    @TempDir
    lateinit var dir: Path

    private fun project() = ProjectBuilder.builder()
        .withProjectDir(dir.toFile())
        .build()
        .also { it.plugins.apply(GradlePlugin::class.java) }

    @Test
    fun `file source resolves to UML-backed MappingData with both views`() {
        val mapFile = dir.resolve("map.tiny")
        Files.writeString(mapFile, "tiny\t2\t0\tofficial\tnamed\nc\ta/A\tb/B\n")

        val ext = project().extensions.getByType(MinecraftExtension::class.java)
        ext.mappings { it.file(mapFile) }
        val data = ext.mappingData().get()

        val uml = data.view<UmlMappingTreeView>()
        assertNotNull(uml)
        assertEquals(listOf("official", "named"), uml!!.tree.namespaces.map { it.name })

        val portable = data.view<xyz.wagyourtail.unimined.api.mapping.MappingTree>()!!
        val seen = mutableListOf<String>()
        portable.accept("official", "named", object : xyz.wagyourtail.unimined.api.mapping.MappingVisitor {
            override fun visitClass(srcName: String, dstName: String) { seen += "$srcName->$dstName" }
        })
        assertEquals(listOf("a/A->b/B"), seen)
        assertEquals(listOf("official", "named"), data.view<xyz.wagyourtail.unimined.api.mapping.MappingTree>()!!.namespaces)
    }

    @Test
    fun `builtin channel fails clearly until manifest machinery lands`() {
        val ext = project().extensions.getByType(MinecraftExtension::class.java)
        ext.mappings { it.official() }
        assertThrows(Exception::class.java) { ext.mappingData().get() }
    }

    @Test
    fun `mapping bundle tasks and outputs are scoped by environment`() {
        val project = project()
        val ext = project.extensions.getByType(MinecraftExtension::class.java)

        val fabric = ext.mappingBundle("fabric", "official", "named").get().asFile.toPath()
        val forge = ext.mappingBundle("forge", "official", "named").get().asFile.toPath()

        assertEquals(
            dir.resolve("build/unimined/mappings/fabric/official-named.json"),
            fabric
        )
        assertEquals(
            dir.resolve("build/unimined/mappings/forge/official-named.json"),
            forge
        )
        assertNotNull(
            project.tasks.findByName("produceMappingBundle_fabric_official_named")
        )
    }

}
