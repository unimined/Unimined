package xyz.wagyourtail.unimined.transform.fabric

import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import xyz.wagyourtail.unimined.minecraft.MinecraftExtension
import java.nio.file.Path

/**
 * JiJ wiring under the fabric claim — the loader descriptor's
 * `nestedJarsKey` convention supplies the metadata coordinates, so
 * `include` works with no explicit `features { nestedJars }` binding.
 */
class NestedJarsFabricWiringTest {

    @TempDir
    lateinit var dir: Path

    private fun project() = ProjectBuilder.builder()
        .withProjectDir(dir.toFile())
        .build()
        .also { project ->
            project.plugins.apply("java")
            project.plugins.apply("xyz.wagyourtail.unimined.remap")
            project.plugins.apply("xyz.wagyourtail.unimined.minecraft")
            project.plugins.apply("xyz.wagyourtail.unimined.transform")
            project.plugins.apply("xyz.wagyourtail.unimined.transform.fabric")
        }

    private fun evaluate(project: org.gradle.api.Project) =
        (project as org.gradle.api.internal.project.ProjectInternal)
            .evaluate()

    @Test
    fun `fabric loader supplies metadata file and nested-jars key`() {
        val project = project()
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        ext.sourceSet("main") {
            it.version.set("1.20.1")
            it.devNamespace.set("named")
            it.modSourceNamespaces.set(listOf("intermediary"))
            it.loader("fabric", "0.16.0")
        }
        evaluate(project)

        val remapJar = project.tasks.getByName("remapJar")
                as xyz.wagyourtail.unimined.remap.task.RemapJarTask
        assertEquals("fabric", remapJar.nestedJarsFormat.get())
        assertEquals("fabric.mod.json", remapJar.nestedJarsMetadataFile.get())
        assertEquals("jars", remapJar.nestedJarsKey.get())
        assertEquals(true, remapJar.nestedJarsRecompress.get())
        // the include bucket feeds it — resolvable, declared deps land
        assertTrue(project.configurations.getByName("include")
            .isCanBeResolved)
    }

    @Test
    fun `fabric identity environment puts the embed on jar`() {
        val project = project()
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        // dev == prod namespace — unobfuscated/identity-style env;
        // remapJar collapses so the JiJ post-pass must hang off `jar`
        ext.sourceSet("main") {
            it.version.set("1.20.1")
            it.devNamespace.set("named")
            it.modSourceNamespaces.set(listOf("named"))
            it.loader("fabric", "0.16.0")
        }
        evaluate(project)

        val jar = project.tasks.getByName("jar") as org.gradle.jvm.tasks.Jar
        val keys = jar.inputs.properties.keys
        assertTrue("nestedJarsFormat" in keys, "$keys")
        assertTrue("nestedJarsMetadataFile" in keys, "$keys")
        assertTrue("nestedJarsKey" in keys, "$keys")
        // format resolves to the descriptor convention
        assertEquals("fabric", jar.inputs.properties["nestedJarsFormat"])
        assertEquals("fabric.mod.json",
            jar.inputs.properties["nestedJarsMetadataFile"])
        assertEquals("jars", jar.inputs.properties["nestedJarsKey"])
    }
}
