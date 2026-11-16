package xyz.wagyourtail.unimined.minecraft

import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import xyz.wagyourtail.unimined.api.mapping.view
import xyz.wagyourtail.unimined.api.spec.Side
import xyz.wagyourtail.unimined.mapping.MappingTree
import xyz.wagyourtail.unimined.mapping.jvms.four.two.one.InternalName
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
    fun `file source resolves to UML-backed MappingData`() {
        val mapFile = dir.resolve("map.tiny")
        Files.writeString(mapFile, "tiny\t2\t0\tofficial\tnamed\nc\ta/A\tb/B\n")

        val ext = project().extensions.getByType(MinecraftExtension::class.java)
        ext.mappings { it.file(mapFile) }
        val data = ext.mappingData().get()

        val tree = data.view<MappingTree>()
        assertNotNull(tree)
        assertEquals(
            listOf("official", "named"),
            tree!!.namespaces.map { it.name }
        )
        val off = tree.namespaces.first { it.name == "official" }
        val named = tree.namespaces.first { it.name == "named" }
        assertEquals(
            "b/B",
            tree.getClass(off, InternalName.unchecked("a/A"))
                ?.names?.get(named)?.value
        )
    }

    @Test
    fun `official builtin resolves to the identity document`() {
        val ext = project().extensions.getByType(MinecraftExtension::class.java)
        ext.mappings { it.official() }
        val data = ext.mappingData().get()
        assertEquals(
            listOf("official"),
            data.view<MappingTree>()!!.namespaces.map { it.name }
        )
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

    @Test
    fun `named-first columns resolve official as the env source namespace`() {
        // retroMCP-era files order `named` first — namespaces.first()
        // must not leak the dev column into sourceNamespace (the
        // raw->dev remap would collapse to identity and ship the
        // obfuscated jar)
        val mapFile = dir.resolve("map.tiny")
        Files.writeString(
            mapFile,
            "tiny\t2\t0\tnamed\tclient\tserver\n" +
                "c\tpkg/Named\tpkg/a\tpkg/b\n"
        )

        // artifacts() -> providerContext needs the java plugin's
        // toolchain service
        val project = project().also { it.plugins.apply("java") }
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        ext.version.set("1.0")
        ext.provider.set("fake")
        ext.side.set(Side.CLIENT)
        ext.mappings { spec ->
            // mirrors retroMCP()'s source wiring
            spec.file(mapFile) {
                mapNamespace("named" to "retroMCP")
                named("retroMCP")
                mapNamespace(
                    "client" to "official:client",
                    "server" to "official:server"
                )
            }
        }

        // envView(client) folds official:client -> official and drops
        // official:server
        val tree = ext.artifacts("main").get().mappings.get()
        assertEquals(
            listOf("retroMCP", "official"),
            tree.namespaces.map { it.name }
        )
        assertEquals("retroMCP", ext.devNamespace("main").get())
        assertEquals("official", ext.sourceNamespace("main").get())
    }

}
