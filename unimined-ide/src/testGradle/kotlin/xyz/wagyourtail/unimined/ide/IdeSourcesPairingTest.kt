package xyz.wagyourtail.unimined.ide

import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import xyz.wagyourtail.unimined.ide.internal.IdeModelResolver
import xyz.wagyourtail.unimined.minecraft.MinecraftExtension
import java.nio.file.Path

/**
 * Dep `-sources.jar` IDE pairing: the MODS classpath group pairs
 * each mod jar with its sources — raw `*SourcesDirect` views for
 * identity envs, the remapped `*SourcesRemapped` views otherwise.
 * Only raw views resolve here (remapped ones need real mappings),
 * so pairing is exercised on an identity env.
 */
class IdeSourcesPairingTest {

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
        }

    private fun serveMod(name: String = "mod", withSources: Boolean = true) {
        val module = dir.resolve("repo/com/example/$name/1.0").toFile()
        module.mkdirs()
        val stub = byteArrayOf(0x50, 0x4B, 0x05, 0x06, *ByteArray(18))
        module.resolve("$name-1.0.jar").writeBytes(stub)
        if (withSources) module.resolve("$name-1.0-sources.jar").writeBytes(stub)
        module.resolve("$name-1.0.pom").writeText(
            "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">" +
                    "<modelVersion>4.0.0</modelVersion>" +
                    "<groupId>com.example</groupId>" +
                    "<artifactId>$name</artifactId>" +
                    "<version>1.0</version>" +
                    "<packaging>jar</packaging></project>"
        )
    }

    private fun repo(project: org.gradle.api.Project) {
        project.repositories.maven { it.url = dir.resolve("repo").toFile().toURI() }
    }

    @Test
    fun `identity env pairs direct mod jars with their published sources`() {
        val project = project()
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        ext.sourceSet("main") {
            it.devNamespace.set("named")
            it.modSourceNamespaces.set(listOf("named"))
        }
        serveMod("mod")
        serveMod("nosrc", withSources = false)
        repo(project)
        project.dependencies.add("modImplementation", "com.example:mod:1.0")
        project.dependencies.add("modImplementation", "com.example:nosrc:1.0")

        assertEquals(
            listOf("modImplementationDirect", "modCompileOnlyDirect", "modRuntimeOnlyDirect"),
            IdeModelResolver.modViewNames(project, ext.main)
        )
        assertEquals(
            listOf("modImplementationSourcesDirect", "modCompileOnlySourcesDirect", "modRuntimeOnlySourcesDirect"),
            IdeModelResolver.modViewNames(project, ext.main, sources = true)
        )

        val entries = IdeModelResolver.modEntries(project, ext.main, ClasspathGroup.MODS)
        val paired = entries.single { it.jar.endsWith("mod-1.0.jar") }
        assertTrue(
            paired.sources!!.endsWith("mod-1.0-sources.jar"),
            "expected paired sources for mod, got ${paired.sources}"
        )
        // a dep with no sources variant contributes its jar with
        // sources=null instead of failing the resolution
        val unpaired = entries.single { it.jar.endsWith("nosrc-1.0.jar") }
        assertNull(unpaired.sources)
    }

    @Test
    fun `non-identity env picks remapped views for jars and sources`() {
        val project = project()
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        ext.sourceSet("main") {
            it.devNamespace.set("named")
            it.modSourceNamespaces.set(listOf("intermediary"))
        }
        assertEquals(
            listOf("modImplementationRemapped", "modCompileOnlyRemapped", "modRuntimeOnlyRemapped"),
            IdeModelResolver.modViewNames(project, ext.main)
        )
        assertEquals(
            listOf("modImplementationSourcesRemapped", "modCompileOnlySourcesRemapped", "modRuntimeOnlySourcesRemapped"),
            IdeModelResolver.modViewNames(project, ext.main, sources = true)
        )
    }

}
