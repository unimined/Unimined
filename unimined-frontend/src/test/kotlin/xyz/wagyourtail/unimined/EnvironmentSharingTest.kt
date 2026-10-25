package xyz.wagyourtail.unimined

import org.gradle.api.Project
import org.gradle.api.artifacts.Dependency
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskProvider
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import xyz.wagyourtail.unimined.api.environment.EnvironmentSection
import xyz.wagyourtail.unimined.api.mapping.MappingTree
import xyz.wagyourtail.unimined.api.spec.MappingsSpec
import xyz.wagyourtail.unimined.api.spec.RunConfigSpec
import xyz.wagyourtail.unimined.minecraft.GradlePlugin as MinecraftPlugin
import xyz.wagyourtail.unimined.minecraft.MinecraftExtension
import xyz.wagyourtail.unimined.minecraft.spi.ArtifactKey
import xyz.wagyourtail.unimined.minecraft.spi.AssetIndex
import xyz.wagyourtail.unimined.minecraft.spi.EnvironmentCapabilities
import xyz.wagyourtail.unimined.minecraft.spi.LaunchMetadata
import xyz.wagyourtail.unimined.minecraft.spi.MinecraftArtifacts
import xyz.wagyourtail.unimined.minecraft.spi.NamespaceMode
import xyz.wagyourtail.unimined.remap.GradlePlugin as RemapPlugin
import xyz.wagyourtail.unimined.remap.RemapExtension
import xyz.wagyourtail.unimined.remap.RemapStepSpec
import xyz.wagyourtail.unimined.remap.spi.RemapSurface
import xyz.wagyourtail.unimined.transform.GradlePlugin as TransformPlugin
import xyz.wagyourtail.unimined.transform.TransformExtension
import xyz.wagyourtail.unimined.transform.spi.MinecraftTransformer
import xyz.wagyourtail.unimined.transform.spi.TransformContext
import xyz.wagyourtail.unimined.transform.task.TransformMinecraftTask
import java.nio.file.Path

private class TestArtifacts(private val project: Project) : MinecraftArtifacts {
    override val artifacts: Map<ArtifactKey, Provider<RegularFile>> = emptyMap()
    override val mappings: Provider<MappingTree> = project.provider { null }
    override val namespaceMode: Provider<NamespaceMode> =
        project.provider { NamespaceMode.REMAPPABLE }
    override val capabilities = project.provider {
        EnvironmentCapabilities(minecraftVersion = "1.0")
    }
    override val libraries: Provider<List<Dependency>> = project.provider { emptyList() }
    override val extraDependencies = project.objects.fileCollection()
    override val extraCompileSources = project.objects.fileCollection()
    override val assets: Provider<AssetIndex> = project.provider { null }
    override val launch: LaunchMetadata? = null
}

private class TestMappings : MappingsSpec {
    override val namespaces = listOf("official", "named")
}

private class TestTransformer(project: Project) : MinecraftTransformer {
    override val transformTaskProvider: TaskProvider<out TransformMinecraftTask> =
        project.tasks.register("sharingTestTransform", TransformMinecraftTask::class.java)

    val seenEnvironments = mutableListOf<String>()

    override fun configure(ctx: TransformContext) {
        seenEnvironments += ctx.environmentId
    }

    override fun transformRun(config: RunConfigSpec) = Unit
}

class EnvironmentSharingTest {

    @TempDir
    lateinit var dir: Path

    private fun project(): Project {
        val project = ProjectBuilder.builder().withProjectDir(dir.toFile()).build()
        project.plugins.apply(MinecraftPlugin::class.java)
        project.plugins.apply(RemapPlugin::class.java)
        project.plugins.apply(TransformPlugin::class.java)
        return project
    }

    @Test
    fun `copyFrom shares selected remap and transform sections`() {
        val project = project()
        val minecraft = project.extensions.getByType(MinecraftExtension::class.java)
        val remap = project.extensions.getByType(RemapExtension::class.java)
        val transform = project.extensions.getByType(TransformExtension::class.java)
        val transformer = TestTransformer(project)

        remap.sourceSet("main").jar { it.remap("tiny") }
        transform.sourceSet("main").transformer(transformer)
        minecraft.sourceSet("fabric").copyFrom(
            "main",
            EnvironmentSection.REMAP,
            EnvironmentSection.TRANSFORMS
        )

        assertEquals(
            listOf("tiny"),
            remap.stepSpecs("fabric", RemapSurface.JAR).get()
                .map { (it as RemapStepSpec).engine }
        )
        transform.build(
            "fabric",
            TestArtifacts(project),
            TestMappings(),
            project.layout.buildDirectory.file("fabric-input.jar")
        )
        assertEquals(listOf("fabric"), transformer.seenEnvironments)
    }

    @Test
    fun `target remap surface overrides an inherited surface`() {
        val project = project()
        val minecraft = project.extensions.getByType(MinecraftExtension::class.java)
        val remap = project.extensions.getByType(RemapExtension::class.java)

        remap.sourceSet("main").jar { it.remap("tiny") }
        remap.sourceSet("main").sources { it.remap("uml") }
        remap.sourceSet("fabric").jar { it.remap("uml") }
        minecraft.sourceSet("fabric").copyFrom("main", EnvironmentSection.REMAP)

        assertEquals(
            listOf("uml"),
            remap.stepSpecs("fabric", RemapSurface.JAR).get()
                .map { (it as RemapStepSpec).engine }
        )
        assertEquals(
            listOf("uml"),
            remap.stepSpecs("fabric", RemapSurface.SOURCES).get()
                .map { (it as RemapStepSpec).engine }
        )
    }

}
