package xyz.wagyourtail.unimined.transform

import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import xyz.wagyourtail.unimined.minecraft.MinecraftExtension
import java.nio.file.Path

/**
 * Verifies the `include` bucket → production-jar JiJ wiring: the
 * environment-scoped configuration, `remapJar`'s nested-jar inputs for
 * remapping environments, and the `jar` post-pass inputs for identity
 * environments (whose remap hop collapses).
 */
class NestedJarsWiringTest {

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

    private fun evaluate(project: org.gradle.api.Project) =
        (project as org.gradle.api.internal.project.ProjectInternal)
            .evaluate()

    @Test
    fun `include bucket is resolvable and env-qualified`() {
        val project = project()
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        ext.sourceSet("main") { it.version.set("1.20.1") }
        project.extensions.getByType(
            org.gradle.api.tasks.SourceSetContainer::class.java
        ).maybeCreate("client")
        ext.sourceSet("client") { it.version.set("1.20.1") }

        val main = project.configurations.getByName("include")
        assertTrue(main.isCanBeResolved)
        assertFalse(main.isCanBeConsumed)

        val client = project.configurations.getByName("clientInclude")
        assertTrue(client.isCanBeResolved)
        assertFalse(client.isCanBeConsumed)
    }

    @Test
    fun `remapJar receives nestedJars inputs from the env spec`() {
        val project = project()
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        ext.sourceSet("main") {
            it.version.set("1.20.1")
            it.devNamespace.set("named")
            it.modSourceNamespaces.set(listOf("intermediary"))
        }
        val transform = project.extensions
            .getByType(TransformExtension::class.java)
        transform.sourceSet("main").features.binding("nestedJars", "fabric")
        transform.sourceSet("main").nestedJars {
            it.recompress.set(false)
        }
        evaluate(project)

        val remapJar = project.tasks.getByName("remapJar")
                as xyz.wagyourtail.unimined.remap.task.RemapJarTask
        assertEquals("fabric", remapJar.nestedJarsFormat.get())
        assertEquals(false, remapJar.nestedJarsRecompress.get())
    }

    @Test
    fun `loader closure reaches the env transform spec`() {
        val project = project()
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        val transform = project.extensions
            .getByType(TransformExtension::class.java)
        var captured: TransformEnvironmentSpec? = null
        ext.sourceSet("main") {
            it.loader("fabric", "0.1") { spec ->
                captured = spec
                spec.nestedJars { nj -> nj.recompress.set(false) }
                spec.binding("nestedJars", "fabric")
            }
        }

        // no evaluate() — the env-side closure fires during DSL eval,
        // before loader-name validation; the assertion targets the
        // spec bridge, not wiring
        assertSame(transform.sourceSet("main"), captured)
        val spec = transform.sourceSet("main")
        assertEquals(false, spec.nestedJars.recompress.get())
        assertEquals("fabric", spec.features.bindings["nestedJars"])
    }

    @Test
    fun `identity environment wires the post-pass onto jar inputs`() {
        val project = project()
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        ext.sourceSet("main") {
            it.version.set("1.20.1")
            it.devNamespace.set("named")
            it.modSourceNamespaces.set(listOf("named"))
        }
        project.extensions.getByType(TransformExtension::class.java)
            .sourceSet("main").features.binding("nestedJars", "fabric")
        evaluate(project)

        val jar = project.tasks.getByName("jar") as org.gradle.jvm.tasks.Jar
        assertTrue(
            "nestedJarsFormat" in jar.inputs.properties.keys,
            "identity jar must carry the JiJ input properties: " +
                    jar.inputs.properties.keys
        )
        // remapJar exists but skips — the embed can't live in it
        val remapJar = project.tasks.getByName("remapJar")
        assertFalse(
            (remapJar as org.gradle.api.internal.TaskInternal)
                .onlyIf.isSatisfiedBy(remapJar)
        )
    }

    @Test
    fun `no binding and no loader metadata means JiJ stays off`() {
        val project = project()
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        ext.sourceSet("main") {
            it.version.set("1.20.1")
            it.devNamespace.set("named")
            it.modSourceNamespaces.set(listOf("intermediary"))
        }
        evaluate(project)

        val remapJar = project.tasks.getByName("remapJar")
                as xyz.wagyourtail.unimined.remap.task.RemapJarTask
        assertEquals(null, remapJar.nestedJarsFormat.orNull)
    }

    @Test
    fun `explicit format override wins over descriptor convention`() {
        val project = project()
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        ext.sourceSet("main") {
            it.version.set("1.20.1")
            it.devNamespace.set("named")
            it.modSourceNamespaces.set(listOf("intermediary"))
        }
        val transform = project.extensions
            .getByType(TransformExtension::class.java)
        transform.sourceSet("main").nestedJars {
            it.format.set("jarjar")
        }
        evaluate(project)

        val remapJar = project.tasks.getByName("remapJar")
                as xyz.wagyourtail.unimined.remap.task.RemapJarTask
        assertEquals("jarjar", remapJar.nestedJarsFormat.get())
        assertNotNull(remapJar.nestedJarEntries)
    }
}
