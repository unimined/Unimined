package xyz.wagyourtail.unimined.minecraft

import groovy.lang.Closure
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import xyz.wagyourtail.unimined.api.environment.EnvironmentSection
import xyz.wagyourtail.unimined.api.mapping.MappingVisitor
import xyz.wagyourtail.unimined.api.mapping.view
import xyz.wagyourtail.unimined.api.spec.Side
import xyz.wagyourtail.unimined.mapping.MappingsSpecImpl
import xyz.wagyourtail.unimined.api.spi.SpiSelectionException
import xyz.wagyourtail.unimined.minecraft.fakes.FakeProvider
import xyz.wagyourtail.unimined.minecraft.spi.ArtifactKey
import java.nio.file.Path
import java.nio.file.Files

class MinecraftExtensionTest {

    @TempDir
    lateinit var dir: Path

    private fun project() = ProjectBuilder.builder()
        .withProjectDir(dir.toFile())
        .build()
        .also { it.plugins.apply(GradlePlugin::class.java) }

    @BeforeEach
    fun resetFake() = FakeProvider.reset()

    @Test
    fun `provider negotiation is lazy and memoized`() {
        val ext = project().extensions.getByType(MinecraftExtension::class.java)
        ext.version.set("1.0")
        val provider = ext.artifacts()
        assertEquals(emptyList<String>(), FakeProvider.calls)
        val artifacts = provider.get()
        assertEquals(listOf("1.0"), FakeProvider.calls)
        provider.get()
        assertEquals(listOf("1.0"), FakeProvider.calls)
        assertNotNull(artifacts.artifacts[ArtifactKey.MERGED])
    }

    @Test
    fun `dsl override selects by name`() {
        val ext = project().extensions.getByType(MinecraftExtension::class.java)
        ext.version.set("9.9") // not supported — override bypasses supports()
        ext.provider.set("fake")
        assertNotNull(ext.artifacts().get())
    }

    @Test
    fun `unsupported spec errors with registered names`() {
        val ext = project().extensions.getByType(MinecraftExtension::class.java)
        ext.version.set("9.9")
        assertThrows<SpiSelectionException> { ext.artifacts().get() }
    }

    @Test
    fun `spec assembles version side and loader`() {
        val ext = project().extensions.getByType(MinecraftExtension::class.java)
        ext.version.set("1.20.1")
        ext.loader("forge", "47.2.0")
        val spec = ext.spec()
        assertEquals("1.20.1", spec.version.version)
        assertEquals("main", spec.environmentId)
        assertEquals("main", spec.sourceSetName)
        assertEquals(Side.MERGED, spec.side)
        assertEquals("forge", spec.loader!!.name)
        assertEquals("47.2.0", spec.loader!!.version)
    }

    @Test
    fun `configured mappings replace provider identity view`() {
        val mappingFile = dir.resolve("mappings.tiny")
        Files.writeString(mappingFile, "tiny\t2\t0\tofficial\tnamed\nc\ta/A\tb/B\n")
        val ext = project().extensions.getByType(MinecraftExtension::class.java)
        ext.version.set("1.0")
        ext.mappings { it.file(mappingFile) }

        val tree = ext.artifacts().get().mappings.get()
        assertEquals(listOf("official", "named"), tree.namespaces)
        val mapped = mutableListOf<String>()
        tree.accept("official", "named", object : MappingVisitor {
            override fun visitClass(srcName: String, dstName: String) {
                mapped += "$srcName->$dstName"
            }
        })
        assertEquals(listOf("a/A->b/B"), mapped)
    }

    @Test
    fun `Groovy closure configures mappings sources`() {
        val mappingFile = dir.resolve("groovy-mappings.tiny")
        Files.writeString(mappingFile, "tiny\t2\t0\tofficial\tnamed\nc\ta/A\tb/B\n")
        val ext = project().extensions.getByType(MinecraftExtension::class.java)
        val closure = object : Closure<Any?>(this) {
            fun doCall() {
                (getDelegate() as MappingsSpecImpl).file(mappingFile)
            }
        }

        ext.mappings(closure)

        assertEquals(
            listOf("official", "named"),
            ext.mappingData().get().view<xyz.wagyourtail.unimined.api.mapping.MappingTree>()!!.namespaces
        )
    }

    @Test
    fun `source set environments keep providers and mappings independent`() {
        val fabricMappings = dir.resolve("fabric.tiny")
        val forgeMappings = dir.resolve("forge.tiny")
        Files.writeString(fabricMappings, "tiny\t2\t0\tofficial\tnamed\nc\ta/A\tfabric/A\n")
        Files.writeString(forgeMappings, "tiny\t2\t0\tofficial\tnamed\nc\ta/A\tforge/A\n")

        val ext = project().extensions.getByType(MinecraftExtension::class.java)
        ext.sourceSet("fabric") { environment ->
            environment.version.set("1.0")
            environment.provider.set("fake")
            environment.loader("fabric", "0.16.0")
            environment.mappings { it.file(fabricMappings) }
        }
        ext.sourceSet("forge") { environment ->
            environment.version.set("1.0")
            environment.provider.set("fake")
            environment.loader("forge", "47.2.0")
            environment.mappings { it.file(forgeMappings) }
        }

        assertEquals("fabric", ext.spec("fabric").environmentId)
        assertEquals("fabric", ext.spec("fabric").sourceSetName)
        assertEquals("fabric", ext.spec("fabric").loader!!.name)
        assertEquals("forge", ext.spec("forge").loader!!.name)

        fun remappedClass(environmentId: String): String {
            var mapped = ""
            ext.artifacts(environmentId).get().mappings.get().accept(
                "official",
                "named",
                object : MappingVisitor {
                    override fun visitClass(srcName: String, dstName: String) {
                        mapped = dstName
                    }
                }
            )
            return mapped
        }
        assertEquals("fabric/A", remappedClass("fabric"))
        assertEquals("forge/A", remappedClass("forge"))
        assertEquals(listOf("fabric", "forge"), FakeProvider.environments)
    }

    @Test
    fun `source set mod dependency buckets are created before or after environment declaration`() {
        val project = ProjectBuilder.builder().withProjectDir(dir.toFile()).build()
        project.plugins.apply("java")
        val sourceSets = project.extensions.getByType(
            org.gradle.api.tasks.SourceSetContainer::class.java
        )
        sourceSets.create("forge")
        project.plugins.apply(GradlePlugin::class.java)
        val ext = project.extensions.getByType(MinecraftExtension::class.java)

        ext.sourceSet("fabric")
        ext.sourceSet("forge")
        sourceSets.create("fabric")

        for (name in listOf(
            "modImplementation",
            "modRuntimeOnly",
            "fabricModImplementation",
            "fabricModRuntimeOnly",
            "forgeModImplementation",
            "forgeModRuntimeOnly"
        )) {
            val configuration = project.configurations.getByName(name)
            assertEquals(false, configuration.isCanBeConsumed, name)
            assertEquals(false, configuration.isCanBeResolved, name)
        }
    }

    @Test
    fun `from inherits default environment sections lazily`() {
        val ext = project().extensions.getByType(MinecraftExtension::class.java)
        ext.main.version.set("1.20.1")
        ext.main.side.set(Side.CLIENT)
        ext.main.provider.set("fake")
        ext.main.loader("merged", "1")

        val fabric = ext.sourceSet("fabric")
        fabric.from("main")
        fabric.loader("fabric", "0.16.0")

        val spec = fabric.spec()
        assertEquals("1.20.1", spec.version.version)
        assertEquals(Side.CLIENT, spec.side)
        assertEquals("fabric", spec.loader!!.name)
        assertEquals("fake", fabric.provider.get())
    }

    @Test
    fun `copyFrom can inherit only mappings`() {
        val mappingFile = dir.resolve("shared.tiny")
        Files.writeString(mappingFile, "tiny\t2\t0\tofficial\tnamed\nc\ta/A\tshared/A\n")
        val ext = project().extensions.getByType(MinecraftExtension::class.java)
        ext.main.mappings { it.file(mappingFile) }

        val fabric = ext.sourceSet("fabric")
        fabric.version.set("1.0")
        fabric.provider.set("fake")
        fabric.copyFrom("main", EnvironmentSection.MAPPINGS)

        assertEquals(
            listOf("official", "named"),
            ext.mappingData("fabric").get().view<xyz.wagyourtail.unimined.api.mapping.MappingTree>()!!.namespaces
        )
    }

    @Test
    fun `combineWith composes source output and package inputs`() {
        val project = ProjectBuilder.builder().withProjectDir(dir.toFile()).build()
        project.plugins.apply("java")
        project.plugins.apply(GradlePlugin::class.java)
        val sourceSets = project.extensions.getByType(
            org.gradle.api.tasks.SourceSetContainer::class.java
        )
        val fabricSourceSet = sourceSets.create("fabric")
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        ext.main.version.set("1.0")
        ext.sourceSet("fabric") { it.version.set("1.0") }

        ext.sourceSet("fabric").combineWith("main")

        val mainOutput = sourceSets.getByName("main").output.files
        assertEquals(
            mainOutput,
            project.configurations.getByName(
                fabricSourceSet.compileClasspathConfigurationName
            ).files
        )
        assertEquals(
            mainOutput,
            project.configurations.getByName(
                fabricSourceSet.runtimeClasspathConfigurationName
            ).files
        )
        assertEquals(
            mainOutput,
            ext.sourceSet("fabric").combinedOutputs.files
        )
    }

    @Test
    fun `combineWith supports a cross-project source set`() {
        val root = ProjectBuilder.builder()
            .withProjectDir(dir.resolve("root").toFile())
            .withName("root")
            .build()
        val common = ProjectBuilder.builder()
            .withParent(root)
            .withName("common")
            .withProjectDir(dir.resolve("common").toFile())
            .build()
        val consumer = ProjectBuilder.builder()
            .withParent(root)
            .withName("consumer")
            .withProjectDir(dir.resolve("consumer").toFile())
            .build()
        common.plugins.apply("java")
        consumer.plugins.apply("java")
        common.plugins.apply(GradlePlugin::class.java)
        consumer.plugins.apply(GradlePlugin::class.java)
        consumer.extensions.getByType(
            org.gradle.api.tasks.SourceSetContainer::class.java
        ).create("fabric")

        common.extensions.getByType(MinecraftExtension::class.java)
            .main.version.set("1.0")
        val consumerMinecraft = consumer.extensions.getByType(MinecraftExtension::class.java)
        val fabric = consumerMinecraft.sourceSet("fabric")
        fabric.provider.set("fake")
        fabric.combineWith(":common:main")

        assertEquals("1.0", fabric.version.get())
        val commonOutput = common.extensions.getByType(
            org.gradle.api.tasks.SourceSetContainer::class.java
        ).getByName("main").output.files
        assertEquals(
            commonOutput,
            fabric.combinedOutputs.files
        )
        assertEquals(
            commonOutput,
            consumer.configurations.getByName("fabricCompileClasspath").files
        )
    }

    @Test
    fun `dependency buckets can be selectively inherited`() {
        val project = ProjectBuilder.builder().withProjectDir(dir.toFile()).build()
        project.plugins.apply("java")
        project.plugins.apply(GradlePlugin::class.java)
        project.extensions.getByType(
            org.gradle.api.tasks.SourceSetContainer::class.java
        ).create("fabric")
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        ext.sourceSet("fabric")

        ext.sourceSet("fabric").copyFrom("main", EnvironmentSection.DEPENDENCIES)

        assertEquals(
            listOf(project.configurations.getByName("modImplementation")),
            project.configurations.getByName("fabricModImplementation").extendsFrom.toList()
        )
    }

    @Test
    fun `combineWith rejects different Minecraft versions`() {
        val ext = project().extensions.getByType(MinecraftExtension::class.java)
        ext.main.version.set("1.0")
        ext.sourceSet("fabric") { environment ->
            environment.version.set("1.1")
            environment.combineWith("main")
        }

        val error = assertThrows<org.gradle.api.GradleException> {
            ext.spec("fabric")
        }
        assertTrue(error.message!!.contains("Minecraft versions differ"))
    }

    @Test
    fun `combineWith rejects different explicit loaders`() {
        val ext = project().extensions.getByType(MinecraftExtension::class.java)
        ext.main.version.set("1.0")
        ext.main.loader("fabric", "0.16.0")
        ext.sourceSet("forge") { environment ->
            environment.version.set("1.0")
            environment.loader("forge", "47.2.0")
            environment.combineWith("main")
        }

        val error = assertThrows<org.gradle.api.GradleException> {
            ext.spec("forge")
        }
        assertTrue(error.message!!.contains("loaders differ"))
    }

    @Test
    fun `combineWith rejects different dev namespaces`() {
        val mainMappings = dir.resolve("main.tiny")
        val fabricMappings = dir.resolve("fabric.tiny")
        Files.writeString(mainMappings, "tiny\t2\t0\tofficial\tnamed\nc\ta/A\tb/B\n")
        Files.writeString(fabricMappings, "tiny\t2\t0\tofficial\tother\nc\ta/A\tc/C\n")

        val ext = project().extensions.getByType(MinecraftExtension::class.java)
        ext.main.version.set("1.0")
        ext.main.provider.set("fake")
        ext.main.mappings { it.file(mainMappings) }
        ext.sourceSet("fabric") { environment ->
            environment.version.set("1.0")
            environment.provider.set("fake")
            environment.mappings { it.file(fabricMappings) }
            environment.combineWith("main")
        }

        val error = assertThrows<org.gradle.api.GradleException> {
            ext.spec("fabric")
        }
        assertTrue(error.message!!.contains("dev namespaces differ"))
    }

}
