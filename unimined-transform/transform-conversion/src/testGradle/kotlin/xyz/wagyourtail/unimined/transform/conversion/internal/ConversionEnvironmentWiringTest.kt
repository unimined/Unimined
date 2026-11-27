package xyz.wagyourtail.unimined.transform.conversion.internal

import org.gradle.api.Action
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import xyz.wagyourtail.unimined.api.spec.Side
import xyz.wagyourtail.unimined.minecraft.MinecraftExtension
import xyz.wagyourtail.unimined.transform.TransformExtension
import xyz.wagyourtail.unimined.transform.loader.LoaderRegistry
import java.nio.file.Path

/**
 * BTA's fabric-loader fork keeps upstream fabric-loader's development
 * discovery contract: classpath mod scanning is gated on
 * `fabric.development`, and split classes/resources dirs are attributed
 * through `fabric.classPathGroups`.
 */
class ConversionEnvironmentWiringTest {

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

    @Test
    fun `bta babric declares fabric classpath groups discovery`() {
        val descriptor = LoaderRegistry.resolveBuiltin("bta-babric")
        assertNotNull(descriptor, "bta-babric descriptor must be registered")
        assertEquals(
            "fabric.classPathGroups",
            descriptor!!.capabilities["classpathGroups"],
            "the runs wiring needs the loader's classpath-groups channel " +
                "to serialize mods{} source-set groups"
        )
    }

    @Test
    fun `bta babric knot runs enable fabric development discovery`() {
        val project = project()
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        project.extensions.getByType(org.gradle.api.tasks.SourceSetContainer::class.java)
            .maybeCreate("bta")
        ext.sourceSet("bta") { env ->
            env.inheritParent.set(false)
            env.bta(Action { })
            env.version.set("8.0.1")
            env.side.set(Side.CLIENT)
            env.loader("bta-babric", "0.18.4-bta.11")
        }
        // production applies the family transform lazily after the env
        // declares its loader; applying it here replays the bound-env
        // hook with loaderSpec already populated
        project.plugins.apply("xyz.wagyourtail.unimined.transform.conversion")

        val mutators = project.extensions.getByType(TransformExtension::class.java)
            .sourceSet("bta").runMutators
        assertTrue(mutators.isNotEmpty(), "bta-babric knot run mutator not registered")

        val spec = object : xyz.wagyourtail.unimined.api.spec.RunConfigSpec {
            override val name = "btaClient"
            override var type = name
            override val prod = false
            override var mainClass: String? = null
            override val javaagents = mutableListOf<java.nio.file.Path>()
            override val jvmArgs = mutableListOf<String>()
            override val programArgs = mutableListOf<String>()
            override val environment = mutableMapOf<String, String>()
            override val properties = mutableMapOf<String, () -> String>()
        }
        mutators.forEach { it.execute(spec) }

        assertEquals("net.fabricmc.loader.impl.launch.knot.KnotClient", spec.mainClass)
        assertTrue(
            "-Dfabric.development=true" in spec.jvmArgs,
            "fabric-loader forks only scan classpath mod metadata in development mode: ${spec.jvmArgs}"
        )
    }
}
