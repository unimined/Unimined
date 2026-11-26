package xyz.wagyourtail.unimined.mod

import groovy.lang.GroovyShell
import org.gradle.api.Action
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import xyz.wagyourtail.unimined.minecraft.MinecraftExtension
import xyz.wagyourtail.unimined.mod.internal.EraMergeWiring
import java.nio.file.Path

class EraMergeWiringTest {

    @TempDir
    lateinit var dir: Path

    private fun project(): org.gradle.api.Project {
        val project = ProjectBuilder.builder().withProjectDir(dir.toFile()).build()
        project.plugins.apply("java")
        project.plugins.apply("xyz.wagyourtail.unimined.minecraft")
        project.plugins.apply("xyz.wagyourtail.unimined.mod")
        return project
    }

    private fun environments(project: org.gradle.api.Project) {
        val minecraft = project.extensions.getByType(MinecraftExtension::class.java)
        minecraft.environments.create("fabric112") {
            it.version.set("1.12.2")
            it.loader("fabric", "0.14.0")
        }
        minecraft.environments.create("forge47") {
            it.version.set("1.4.7")
            it.loader("forge", "1.0.0")
        }
        minecraft.environments.create("shared") {
            it.loader("fabric", "0.14.0")
        }
        minecraft.environments.create("datagen") {
            it.version.set("1.12.2")
            it.loader("fabric", "0.14.0")
            it.isAuxEnv.set(true)
        }
        minecraft.environments.create("disabled") {
            it.version.set("1.12.2")
            it.loader("fabric", "0.14.0")
            it.defaultRemapJar.set(false)
        }
    }

    @Test
    fun `default selection keeps output-producing versioned non-auxiliary environments`() {
        val project = project()
        environments(project)
        val mod = project.extensions.getByType(ModExtension::class.java)
        val plan = EraMergeWiring.select(mod.chimeraJarSpec, mod.eraMergeEnvironments)
        assertEquals(listOf("fabric112", "forge47"), plan.map { it.name })
    }

    @Test
    fun `explicit era list replaces automatic selection exactly`() {
        val project = project()
        environments(project)
        val mod = project.extensions.getByType(ModExtension::class.java)
        mod.chimeraJarSpec.eras("forge47")
        val plan = EraMergeWiring.select(mod.chimeraJarSpec, mod.eraMergeEnvironments)
        assertEquals(listOf("forge47"), plan.map { it.name })
    }

    @Test
    fun `capability partition nests fabric-family loaders and flattens others`() {
        assertEquals("nest", EraMergeWiring.effectivePartition(null, "fabric"))
        assertEquals("nest", EraMergeWiring.effectivePartition(null, "legacyfabric"))
        assertEquals("flatten", EraMergeWiring.effectivePartition(null, "forge"))
        val project = ProjectBuilder.builder().build()
        val override = project.objects.newInstance(EraInputSpec::class.java)
        override.partition.set("flatten")
        assertEquals("flatten", EraMergeWiring.effectivePartition(override, "fabric"))
    }

    @Test
    fun `kotlin and groovy dsl configure era merge model and task exists`() {
        val project = project()
        val mod = project.extensions.getByType(ModExtension::class.java)
        mod.chimeraJar(Action {
            it.eras("fabric112")
            it.era("fabric112") { era -> era.partition.set("flatten") }
            it.nested { nested ->
                nested.id.set("example-inner")
                nested.nestedEntry("META-INF/mm.jar")
            }
            it.manifest { manifest -> manifest.attribute("MixinConfigs", "example.mixins.json") }
        })
        assertTrue(project.tasks.findByName("chimeraJar") != null)
        assertEquals(listOf("fabric112"), mod.chimeraJarSpec.eraNames.get())
        assertEquals("flatten", mod.chimeraJarSpec.eraOverrides["fabric112"]!!.partition.get())
        assertEquals("example-inner", mod.chimeraJarSpec.nestedJar.id.get())
        assertEquals("example.mixins.json", mod.chimeraJarSpec.manifestSpec.attributes.get()["MixinConfigs"])

        GroovyShell(javaClass.classLoader).apply {
            setVariable("mod", mod)
            evaluate("""
                mod.mergedEraJar {
                    eras('fabric112')
                    era('fabric112') { partition.set('nest') }
                    nested { id.set('groovy-inner') }
                    manifest { attribute('FMLCorePluginContainsFMLMod', true) }
                }
            """.trimIndent())
        }
        assertEquals("nest", mod.mergedEraJarSpec.eraOverrides["fabric112"]!!.partition.get())
        assertEquals("groovy-inner", mod.mergedEraJarSpec.nestedJar.id.get())
        assertEquals(
            "true",
            mod.mergedEraJarSpec.manifestSpec.attributes.get()["FMLCorePluginContainsFMLMod"]
        )
    }

    @Test
    fun `nested jar path defaults to sanitized project name and honors override`() {
        val named = ProjectBuilder.builder()
            .withProjectDir(dir.resolve("named").toFile().apply { mkdirs() })
            .withName("My Cool Mod")
            .build()
        named.plugins.apply("java")
        named.plugins.apply("xyz.wagyourtail.unimined.minecraft")
        named.plugins.apply("xyz.wagyourtail.unimined.mod")
        val mod = named.extensions.getByType(ModExtension::class.java)
        val manualInput = named.tasks.register("manualInput", org.gradle.jvm.tasks.Jar::class.java)
        mod.chimeraJar(Action {
            it.eras("manual")
            it.era("manual") { era ->
                era.jar.set(manualInput.flatMap { jar -> jar.archiveFile })
                era.loader.set("forge")
            }
        })
        (named as org.gradle.api.internal.project.ProjectInternal).evaluate()
        val task = named.tasks.named("chimeraJar", xyz.wagyourtail.unimined.mod.task.MergeEraJarsTask::class.java).get()
        assertEquals("META-INF/jars/my-cool-mod-nested.jar", task.nestedPath.get())

        val overridden = ProjectBuilder.builder()
            .withProjectDir(dir.resolve("overridden").toFile().apply { mkdirs() })
            .withName("other")
            .build()
        overridden.plugins.apply("java")
        overridden.plugins.apply("xyz.wagyourtail.unimined.minecraft")
        overridden.plugins.apply("xyz.wagyourtail.unimined.mod")
        val mod2 = overridden.extensions.getByType(ModExtension::class.java)
        val manualInput2 = overridden.tasks.register("manualInput", org.gradle.jvm.tasks.Jar::class.java)
        mod2.chimeraJar(Action {
            it.eras("manual")
            it.era("manual") { era ->
                era.jar.set(manualInput2.flatMap { jar -> jar.archiveFile })
                era.loader.set("forge")
            }
            it.nested { nested -> nested.path.set("META-INF/custom.jar") }
        })
        (overridden as org.gradle.api.internal.project.ProjectInternal).evaluate()
        val task2 = overridden.tasks.named("chimeraJar", xyz.wagyourtail.unimined.mod.task.MergeEraJarsTask::class.java).get()
        assertEquals("META-INF/custom.jar", task2.nestedPath.get())
    }
}
