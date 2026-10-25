package xyz.wagyourtail.unimined.transform.fabric

import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import xyz.wagyourtail.unimined.minecraft.MinecraftExtension
import xyz.wagyourtail.unimined.remap.AccessRemapStepSpec
import xyz.wagyourtail.unimined.remap.RemapExtension
import xyz.wagyourtail.unimined.remap.RemapStepSpec
import xyz.wagyourtail.unimined.remap.spi.RemapSurface
import xyz.wagyourtail.unimined.transform.TransformExtension
import java.nio.file.Path

/**
 * Verifies the fabric transform's per-environment contributions: loader
 * dependency injection, mod-source namespace default, contributed JAR
 * pipeline steps, and the run mutator — all gated on `loader("fabric")`.
 */
class FabricEnvironmentWiringTest {

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

    private fun fabricEnv(
        project: org.gradle.api.Project,
        ext: MinecraftExtension
    ) = org.gradle.api.tasks.SourceSetContainer::class.java.let { type ->
        project.extensions.getByType(type).create("fabric")
        ext.sourceSet("fabric") { env ->
            env.version.set("1.20.1")
            env.loader("fabric", "0.16.0")
        }
    }

    @Test
    fun `apply contributes fabric repo and intermediary mod-source default`() {
        val project = project()
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        assertEquals(listOf("intermediary"), ext.loaderModSourceNamespaces["fabric"])
        assertTrue(
            project.repositories.any { (it as? org.gradle.api.artifacts.repositories.MavenArtifactRepository)?.url.toString() == "https://maven.fabricmc.net/" }
        )
    }

    @Test
    fun `fabric env gets loader dep, contributed steps, run mutator`() {
        val project = project()
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        val env = fabricEnv(project, ext)

        val remap = project.extensions.getByType(RemapExtension::class.java)
        val steps = remap.pipelineSteps("fabric", RemapSurface.JAR).get()
        assertEquals(3, steps.size)
        assertTrue(steps[0] is RemapStepSpec)
        assertTrue(steps[1] is ApplyAccessWidenerStepSpec)
        assertTrue(steps[2] is AccessRemapStepSpec)

        val transform = project.extensions.getByType(TransformExtension::class.java)
        assertEquals(1, transform.sourceSet("fabric").runMutators.size)

        assertEquals("fabric", env.loaderSpec.orNull?.name)
        assertEquals(
            listOf("intermediary"),
            ext.modSourceNamespaces("fabric").get()
        )
        assertTrue(env.mappingsSpec.defaultSources.isNotEmpty())
        assertNotNull(project.configurations.findByName("fabricModAnalysisFabric"))

        // resolving the analysis view runs the loader-dep hook end-to-end
        val repo = dir.resolve("repo").toFile()
        val jarDir = repo.resolve("net/fabricmc/fabric-loader/0.16.0")
        jarDir.mkdirs()
        jarDir.resolve("fabric-loader-0.16.0.jar").writeBytes(
            byteArrayOf(0x50, 0x4B, 0x05, 0x06, *ByteArray(18))
        )
        project.repositories.maven {
            it.url = repo.toURI()
            it.metadataSources { sources -> sources.artifact() }
        }
        val resolved = project.configurations.getByName("fabricModAnalysisFabric")
            .resolve().map { it.name }
        assertEquals(listOf("fabric-loader-0.16.0.jar"), resolved)
    }

    @Test
    fun `non-fabric environments get no loader dep`() {
        val project = project()
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        ext.version.set("1.20.1")

        val modImpl = project.configurations.getByName("modImplementation")
        assertTrue(modImpl.dependencies.isEmpty())
    }

}
