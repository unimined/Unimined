package xyz.wagyourtail.unimined.transform

import org.gradle.api.Project
import org.gradle.api.artifacts.Dependency
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskProvider
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import xyz.wagyourtail.unimined.api.spec.MappingsSpec
import xyz.wagyourtail.unimined.api.spec.RunConfigSpec
import xyz.wagyourtail.unimined.mapping.MappingTree
import xyz.wagyourtail.unimined.minecraft.spi.*
import xyz.wagyourtail.unimined.transform.spi.MinecraftTransformer
import xyz.wagyourtail.unimined.transform.spi.TransformContext
import xyz.wagyourtail.unimined.transform.spi.TransformDecorator
import xyz.wagyourtail.unimined.transform.task.TransformMinecraftTask
import java.nio.file.Path

private class FakeArtifacts(private val project: Project) : MinecraftArtifacts {
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

private class FakeMappings : MappingsSpec {
    override val namespaces = listOf("official", "named")
}

private class FakeTransformer(
    val tag: String,
    private val project: Project,
    private val order: MutableList<String>,
    private val emitName: String? = null
) : MinecraftTransformer {

    override val transformTaskProvider: TaskProvider<out TransformMinecraftTask> =
        project.tasks.register("transform_$tag", TransformMinecraftTask::class.java)

    val seenInputs = mutableListOf<List<String>>()
    val seenEnvironments = mutableListOf<String>()

    init {
        order.add("declare:$tag")
    }

    override fun configure(ctx: TransformContext) {
        seenEnvironments += ctx.environmentId
        seenInputs += ctx.inputs.map { it.name }
        emitName?.let {
            ctx.emit(it, project.layout.buildDirectory.file("$it.jar"))
        }
    }

    override fun transformRun(config: RunConfigSpec) {
        order.add("run:$tag")
    }

}

private class FakeDecorator(
    val tag: String,
    private val project: Project,
    private val order: MutableList<String>
) : TransformDecorator {
    override fun apply(input: Provider<RegularFile>, ctx: TransformContext): Provider<RegularFile> {
        order += "decorator:$tag"
        return project.layout.buildDirectory.file("decorated-$tag.jar")
    }
}

class TransformPipelineTest {

    @TempDir
    lateinit var dir: Path

    private fun project() = ProjectBuilder.builder()
        .withProjectDir(dir.toFile())
        .build()
        .also { it.plugins.apply(GradlePlugin::class.java) }

    @Test
    fun `pipeline wires transforms in order and chains decorators`() {
        val project = project()
        val ext = project.extensions.getByType(TransformExtension::class.java)
        val order = mutableListOf<String>()

        val input = project.layout.buildDirectory.file("input.jar")
        val t1 = FakeTransformer("a", project, order, emitName = "a-extra")
        val t2 = FakeTransformer("b", project, order)
        ext.transformer(t1)
        ext.transformer(t2)
        ext.decorator(FakeDecorator("aw", project, order))

        val pipeline = ext.build(FakeArtifacts(project), FakeMappings(), input)

        // decorators applied once per transformer, after its configure
        assertEquals(
            listOf("declare:a", "declare:b", "decorator:aw", "decorator:aw"),
            order
        )
        // t2 sees t1's emitted outputs plus the decorated main artifact
        assertEquals(listOf(listOf("a-extra", "jar")), t2.seenInputs)
        // emitted extras land in the outputs map
        assertEquals("a-extra.jar", pipeline.output("a-extra")!!.get().asFile.name)
        // main artifact = decorated output of last transformer
        assertEquals("decorated-aw.jar", pipeline.output.get().asFile.name)
        // t1's input wired to the provider artifact; t2's to decorated t1
        assertEquals(
            "input.jar",
            t1.transformTaskProvider.get().inputArtifact.get().asFile.name
        )
        assertEquals(
            "decorated-aw.jar",
            t2.transformTaskProvider.get().inputArtifact.get().asFile.name
        )
    }

    @Test
    fun `transformRun delegates in declaration order`() {
        val project = project()
        val ext = project.extensions.getByType(TransformExtension::class.java)
        val order = mutableListOf<String>()
        ext.transformer(FakeTransformer("x", project, order))
        ext.transformer(FakeTransformer("y", project, order))

        val cfg = object : RunConfigSpec {
            override val name = "client"
            override var mainClass: String? = null
            override val jvmArgs = mutableListOf<String>()
            override val programArgs = mutableListOf<String>()
            override val environment = mutableMapOf<String, String>()
            override val properties = mutableMapOf<String, () -> String>()
        }
        ext.transformRun(cfg)
        assertEquals(listOf("declare:x", "declare:y", "run:x", "run:y"), order)
    }

    @Test
    fun `transform pipelines are isolated by source set`() {
        val project = project()
        val ext = project.extensions.getByType(TransformExtension::class.java)
        val order = mutableListOf<String>()
        val fabric = FakeTransformer("fabric", project, order)
        val forge = FakeTransformer("forge", project, order)
        ext.sourceSet("fabric") { it.transformer(fabric) }
        ext.sourceSet("forge") { it.transformer(forge) }

        ext.build(
            "fabric", FakeArtifacts(project), FakeMappings(),
            project.layout.buildDirectory.file("fabric-input.jar")
        )
        ext.build(
            "forge", FakeArtifacts(project), FakeMappings(),
            project.layout.buildDirectory.file("forge-input.jar")
        )

        assertEquals(listOf("fabric"), fabric.seenEnvironments)
        assertEquals(listOf("forge"), forge.seenEnvironments)
        assertEquals(listOf("declare:fabric", "declare:forge"), order)
    }

}
