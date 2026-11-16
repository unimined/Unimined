package xyz.wagyourtail.unimined.minecraft

import groovy.lang.Closure
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import xyz.wagyourtail.unimined.api.environment.EnvironmentReference
import xyz.wagyourtail.unimined.api.spec.DeclaredSource
import xyz.wagyourtail.unimined.api.spec.Side
import xyz.wagyourtail.unimined.minecraft.fakes.FakeProvider
import java.nio.file.Path

/**
 * Env-binding overload matrix — the `sourceSet(...)`/`datagen`/
 * `gametest` verb shapes in both languages, the `inheritParent`
 * opt-out, the `dist`/`legacydist`/`devbundle` preset wrappers
 * (DSL → provider/product selection → `spec().declaredSource`), and
 * `useToolchains` global→env forwarding.
 */
class EnvironmentBindingMatrixTest {

    @TempDir
    lateinit var dir: Path

    private fun project() = ProjectBuilder.builder()
        .withProjectDir(dir.toFile())
        .build()
        .also {
            // registers JavaToolchainService — real consumers apply java
            // themselves; the plugin does not
            it.plugins.apply("java")
            it.plugins.apply(GradlePlugin::class.java)
        }

    private fun evaluate(project: org.gradle.api.Project) =
        (project as org.gradle.api.internal.project.ProjectInternal).evaluate()

    private fun minecraft(project: org.gradle.api.Project) =
        project.extensions.getByType(MinecraftExtension::class.java)

    private fun envClosure(block: MinecraftEnvironment.() -> Unit) =
        object : Closure<Any?>(this) {
            fun doCall() {
                (getDelegate() as MinecraftEnvironment).block()
            }
        }

    private fun ref(project: org.gradle.api.Project, envId: String) =
        EnvironmentReference(project.path, envId)

    @BeforeEach
    fun resetFake() = FakeProvider.reset()

    @Test
    fun `sourceSet action form creates a main-parented environment`() {
        val ext = project().extensions.getByType(MinecraftExtension::class.java)
        val fabric = ext.sourceSet("fabric") {
            it.version.set("1.0")
        }
        assertSame(fabric, ext.environments.getByName("fabric"))
        assertEquals("main", fabric.parentEnvId)
        assertEquals("1.0", fabric.version.get())
    }

    @Test
    fun `nested sourceSet blocks parent to the enclosing environment`() {
        val project = project()
        val ext = minecraft(project)
        ext.version.set("1.0")

        ext.sourceSet("outer") { outer ->
            outer.sourceSet("inner") {}
        }
        val inner = ext.environments.getByName("inner")

        assertEquals("outer", inner.parentEnvId,
            "the nested declaration's parent is the enclosing env")
        // lazy version conventions chain through the lexical parents
        assertEquals("1.0", inner.version.get())

        evaluate(project)
        assertEquals(setOf(ref(project, "main")),
            ext.environments.getByName("outer").combinedWith)
        assertEquals(setOf(ref(project, "outer")), inner.combinedWith)
    }

    @Test
    fun `sourceSet groovy closure twins configure and parent nested envs`() {
        val project = project()
        val ext = minecraft(project)

        val outer = ext.sourceSet("outer", envClosure {
            version.set("1.0")
        })
        assertEquals("main", outer.parentEnvId)
        assertEquals("1.0", outer.version.get())

        // env-level Closure twin — the enclosing env is the parent
        val inner = outer.sourceSet("inner", envClosure {})
        assertEquals("outer", inner.parentEnvId)
        assertEquals("1.0", inner.version.get())
    }

    @Test
    fun `datagen declares an aux env bound to its parent`() {
        val project = project()
        val ext = minecraft(project)
        val main = ext.main
        main.version.set("1.0")
        main.loader("fabric", "0.16.0")

        val data = main.datagen()

        assertEquals("data", data.environmentId)
        assertEquals("main", data.parentEnvId)
        assertTrue(data.isAuxEnv.get())
        assertFalse(data.clientDatagen)
        assertTrue(main.datagenEnvs.single() === data)

        evaluate(project)

        // the implicit-parent combine lands post-DSL
        assertEquals(setOf(ref(project, "main")), data.combinedWith)
        // aux envs inherit the parent's loader + dependency buckets
        assertEquals("fabric", data.loaderSpec.get().name)
        val configurations = project.configurations
        assertTrue(
            configurations.getByName("modImplementation") in
                    configurations.getByName("dataModImplementation").extendsFrom,
            "dataModImplementation should extend modImplementation")
        assertTrue(
            configurations.getByName("implementation") in
                    configurations.getByName("dataImplementation").extendsFrom,
            "dataImplementation should extend implementation")
        // the parent's resources gain src/<env>/generated (loom parity)
        val resources = project.extensions.getByType(
            org.gradle.api.tasks.SourceSetContainer::class.java
        ).getByName("main").resources.srcDirs
        assertTrue(
            project.file("src/main/generated") in resources,
            "main resources should carry src/main/generated: $resources")
    }

    @Test
    fun `datagen on a named env uses the env prefix and client flag`() {
        val project = project()
        val ext = minecraft(project)
        val fabric = ext.sourceSet("fabric") {}

        val data = fabric.datagen(client = true)

        assertEquals("fabricData", data.environmentId)
        assertEquals("fabric", data.parentEnvId)
        assertTrue(data.isAuxEnv.get())
        assertTrue(data.clientDatagen)
        assertTrue(fabric.datagenEnvs.single() === data)

        evaluate(project)
        assertEquals(setOf(ref(project, "fabric")), data.combinedWith)
        val resources = project.extensions.getByType(
            org.gradle.api.tasks.SourceSetContainer::class.java
        ).getByName("fabric").resources.srcDirs
        assertTrue(
            project.file("src/fabric/generated") in resources,
            "fabric resources should carry src/fabric/generated: $resources")
    }

    @Test
    fun `datagen and gametest groovy closure twins`() {
        val ext = project().extensions.getByType(MinecraftExtension::class.java)
        val main = ext.main

        val data = main.datagen("data", false, envClosure {})
        assertTrue(data.isAuxEnv.get())
        assertTrue(main.datagenEnvs.single() === data)

        val gametest = main.gametest("gametest", envClosure {})
        assertEquals("gametest", gametest.environmentId)
        assertTrue(gametest.isAuxEnv.get())
        assertTrue(main.gametestEnvs.single() === gametest)

        val fabric = ext.sourceSet("fabric") {}
        val fabricGametest = fabric.gametest("fabricGametest", envClosure {})
        assertEquals("fabricGametest", fabricGametest.environmentId)
        assertEquals("fabric", fabricGametest.parentEnvId)
    }

    @Test
    fun `a manually flagged aux env also inherits the parent's loader and buckets`() {
        val project = project()
        val ext = minecraft(project)
        ext.main.loader("fabric", "0.16.0")
        val aux = ext.sourceSet("aux") { it.isAuxEnv.set(true) }

        evaluate(project)

        assertEquals(setOf(ref(project, "main")), aux.combinedWith)
        assertEquals("fabric", aux.loaderSpec.get().name)
        val configurations = project.configurations
        assertTrue(
            configurations.getByName("modImplementation") in
                    configurations.getByName("auxModImplementation").extendsFrom)
    }

    @Test
    fun `inheritParent false opts the env out of the implicit combine and lazy conventions`() {
        val project = project()
        val ext = minecraft(project)
        ext.main.version.set("1.0")
        ext.main.side.set(Side.CLIENT)
        ext.main.provider.set("fake")

        val optedOut = ext.sourceSet("detached") { it.inheritParent.set(false) }
        val inheriting = ext.sourceSet("attached") {}

        evaluate(project)

        // nothing flows: no combine ref, no lazy version/side/provider
        assertEquals(emptySet<EnvironmentReference>(), optedOut.combinedWith)
        assertNull(optedOut.version.orNull)
        assertEquals(Side.MERGED, optedOut.side.get())
        assertNull(optedOut.provider.orNull)

        // the default-inherit counterpart gets all of it
        assertEquals(setOf(ref(project, "main")), inheriting.combinedWith)
        assertEquals("1.0", inheriting.version.get())
        assertEquals(Side.CLIENT, inheriting.side.get())
        assertEquals("fake", inheriting.provider.get())
    }

    @Test
    fun `conversion dist presets select the provider and product`() {
        val ext = project().extensions.getByType(MinecraftExtension::class.java)
        val env = ext.main

        env.bta { it.version.set("7.3") }
        assertEquals("conversion", env.provider.get())
        assertEquals("minecraft:bta", env.product.get())
        assertEquals("7.3", env.version.get())

        env.reindev {}
        assertEquals("conversion", env.provider.get())
        assertEquals("minecraft:reindev", env.product.get())

        env.nsss { it.file("nsss.jar") }
        assertEquals("conversion", env.provider.get())
        assertEquals("minecraft:nsss", env.product.get())
        assertTrue(env.legacyDist.source is DeclaredSource.LocalFile,
            "DistSpec.file must route to the declared-source seam")
    }

    @Test
    fun `dist presets on a named env and the extension delegates`() {
        val ext = project().extensions.getByType(MinecraftExtension::class.java)
        val fabric = ext.sourceSet("fabric") {
            it.reindev { d -> d.version.set("1.0") }
        }
        assertEquals("conversion", fabric.provider.get())
        assertEquals("minecraft:reindev", fabric.product.get())
        assertEquals("1.0", fabric.version.get())

        // extension-level delegates hit main
        ext.bta {}
        assertEquals("conversion", ext.main.provider.get())
        assertEquals("minecraft:bta", ext.main.product.get())
        ext.nsss {}
        assertEquals("minecraft:nsss", ext.main.product.get())
        ext.reindev {}
        assertEquals("minecraft:reindev", ext.main.product.get())
    }

    @Test
    fun `custom dist conventions the product without forcing a provider`() {
        val ext = project().extensions.getByType(MinecraftExtension::class.java)
        val env = ext.main

        env.custom("totalconv") { it.file("tc.jar") }

        assertEquals("custom:totalconv", env.product.get())
        assertNull(env.provider.orNull,
            "custom must not pin a provider — the declared-source seam negotiates")
        assertTrue(env.legacyDist.source is DeclaredSource.LocalFile)
        env.version.set("1.0")
        val declared = env.spec().declaredSource
        assertNotNull(declared)
        assertEquals("custom:totalconv", env.spec().product)
    }

    @Test
    fun `devbundle selects the devbundle provider and format`() {
        val project = project()
        val env = minecraft(project).main

        env.devbundle { it.file("bundle.zip") }

        assertEquals("devbundle", env.provider.get())
        assertEquals("devbundle", env.legacyDist.format)
        val source = env.legacyDist.source as DeclaredSource.LocalFile
        env.version.set("1.0")
        val declared = env.spec().declaredSource!!
        assertEquals("devbundle", declared.format)
        assertEquals(
            project.file("bundle.zip").absolutePath,
            (declared.source as DeclaredSource.LocalFile).path)
    }

    @Test
    fun `legacydist declares sources and selects the provider`() {
        val project = project()
        val ext = minecraft(project)
        val env = ext.main
        env.version.set("1.0")

        env.legacydist { it.file("dist/game.jar") }
        assertEquals("legacydist", env.provider.get(),
            "legacydist { } implies the legacydist provider")
        val local = env.legacyDist.source as DeclaredSource.LocalFile
        assertEquals(project.file("dist/game.jar").absolutePath, local.path,
            "file() absolutizes at declaration — workers have no project cwd")

        val declared = env.spec().declaredSource!!
        assertEquals(local, declared.source)
        assertEquals("official", declared.namespace)
        assertNull(declared.format)

        // url + producer forms
        env.legacydist { it.url("https://example.test/dist.jar", "abc123") }
        assertEquals(DeclaredSource.Url("https://example.test/dist.jar", "abc123"),
            env.legacyDist.source)
        env.legacydist {
            it.producer("mybuilder", "--flag", jvmArgs = listOf("-Xmx1g"), output = "out.jar")
        }
        assertEquals(
            DeclaredSource.Producer("mybuilder", listOf("--flag"), listOf("-Xmx1g"), "out.jar"),
            env.legacyDist.source)
        // maven stays a declaration until spec() materializes it —
        // never resolve in a wiring test
        env.legacydist { it.maven("g:a:1") }
        assertEquals(DeclaredSource.Maven("g:a:1"), env.legacyDist.source)
    }

    @Test
    fun `legacydist format selects a registered provider or stays a content hint`() {
        val ext = project().extensions.getByType(MinecraftExtension::class.java)
        val env = ext.main

        // "fake" is registered on the test classpath — a registered
        // provider name wins
        env.legacydist { it.format = "fake" }
        assertEquals("fake", env.provider.get())

        // content hints that aren't provider names keep legacydist
        env.legacydist { it.format = "obf" }
        assertEquals("legacydist", env.provider.get())
        assertEquals("obf", env.legacyDist.format)
    }

    @Test
    fun `dist groovy closure twins`() {
        val ext = project().extensions.getByType(MinecraftExtension::class.java)
        val env = ext.main

        env.bta(object : Closure<Any?>(this) {
            fun doCall() {
                (getDelegate() as DistSpec).version.set("7.3")
            }
        })
        assertEquals("conversion", env.provider.get())
        assertEquals("minecraft:bta", env.product.get())
        assertEquals("7.3", env.version.get())

        env.legacydist(object : Closure<Any?>(this) {
            fun doCall() {
                (getDelegate() as LegacyDistSpec).file("d.jar")
            }
        })
        assertEquals("legacydist", env.provider.get())
        assertTrue(env.legacyDist.source is DeclaredSource.LocalFile)

        env.devbundle(object : Closure<Any?>(this) {
            fun doCall() {
                (getDelegate() as DistSpec).file("b.zip")
            }
        })
        assertEquals("devbundle", env.provider.get())
        assertEquals("devbundle", env.legacyDist.format)
    }

    @Test
    fun `useToolchains forwards the global default and honors env overrides`() {
        val ext = project().extensions.getByType(MinecraftExtension::class.java)

        // global default flows to every env
        assertTrue(ext.useToolchains.get())
        assertTrue(ext.main.useToolchains.get())
        val fabric = ext.sourceSet("fabric") {}
        assertTrue(fabric.useToolchains.get())

        // the global kill-switch propagates lazily (convention, not copy)
        ext.useToolchains.set(false)
        assertFalse(ext.main.useToolchains.get())
        assertFalse(fabric.useToolchains.get())

        // per-env override wins over the global
        fabric.useToolchains.set(true)
        assertTrue(fabric.useToolchains.get())
        assertFalse(ext.main.useToolchains.get())
    }

}
