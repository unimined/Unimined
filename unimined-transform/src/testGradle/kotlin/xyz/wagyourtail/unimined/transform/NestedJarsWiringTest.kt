package xyz.wagyourtail.unimined.transform

import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import xyz.wagyourtail.unimined.minecraft.MinecraftExtension
import java.io.File
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

    /** A project whose `include` conf carries [deps] + a minimal
     *  env, fully evaluated — `remapJar.nestedJarEntries` is then the
     *  validating provider under test. */
    private fun jarjarProject(
        vararg deps: String
    ): org.gradle.api.Project {
        val project = project()
        val repoDir = File(dir.toFile(), "repo")
        project.repositories.maven { it.url = repoDir.toURI() }
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        ext.sourceSet("main") {
            it.version.set("1.20.1")
            it.devNamespace.set("named")
            it.modSourceNamespaces.set(listOf("intermediary"))
        }
        project.extensions.getByType(TransformExtension::class.java)
            .sourceSet("main").features.binding("nestedJars", "fabric")
        deps.forEach { dep ->
            if (!dep.contains(":")) return@forEach
            val (g, a, v) = dep.split(":")
            // dynamic/range selectors resolve to 1.0 via metadata
            val dynamic = v.contains("+") || v.startsWith("[") ||
                    v.startsWith("(")
            val realV = if (dynamic) "1.0" else v
            if (dynamic) {
                File(repoDir, "${g.replace('.', '/')}/$a")
                    .apply { mkdirs() }
                    .resolve("maven-metadata.xml").writeText(
                        "<metadata><groupId>$g</groupId>" +
                                "<artifactId>$a</artifactId>" +
                                "<versioning><release>1.0</release>" +
                                "<versions><version>1.0</version>" +
                                "</versions></versioning></metadata>")
            }
            val d = File(
                repoDir, "${g.replace('.', '/')}/$a/$realV"
            ).apply { mkdirs() }
            File(d, "$a-$realV.jar").writeBytes(
                byteArrayOf(0x50, 0x4B, 5, 6, *ByteArray(18)))
            File(d, "$a-$realV.pom").writeText(
                "<project><modelVersion>4.0.0</modelVersion>" +
                        "<groupId>$g</groupId><artifactId>$a</artifactId>" +
                        "<version>$realV</version></project>")
            project.dependencies.add("include", "$g:$a:$v")
        }
        evaluate(project)
        return project
    }

    /** Provider `.get()` wraps failures in PropertyQueryException —
     *  walk the cause chain for the GradleException we threw. */
    private fun failureMessage(block: () -> Unit): String {
        val e = assertThrows(Exception::class.java) { block() }
        var cur: Throwable? = e
        while (cur != null) {
            if (cur is org.gradle.api.GradleException) {
                return cur.message ?: "GradleException (no message)"
            }
            cur = cur.cause
        }
        throw AssertionError("no GradleException in cause chain", e)
    }

    @Test
    fun `open or dynamic declared versions fail with guidance`() {
        val project = jarjarProject("g:a:1.+")
        val remapJar = project.tasks.getByName("remapJar")
                as xyz.wagyourtail.unimined.remap.task.RemapJarTask
        val msg = failureMessage { remapJar.nestedJarEntries.get() }
        assertTrue(msg.contains("version.range"), msg)
        assertTrue(msg.contains("1.+"), msg)
    }

    @Test
    fun `open-ended declared range fails`() {
        val project = jarjarProject("g:b:[1.0,)")
        val remapJar = project.tasks.getByName("remapJar")
                as xyz.wagyourtail.unimined.remap.task.RemapJarTask
        failureMessage { remapJar.nestedJarEntries.get() }
    }

    @Test
    fun `closed declared range resolves entries`() {
        val project = jarjarProject("g:c:1.0")
        val remapJar = project.tasks.getByName("remapJar")
                as xyz.wagyourtail.unimined.remap.task.RemapJarTask
        val entries = remapJar.nestedJarEntries.get()
        assertEquals(1, entries.size)
        assertEquals("g:c:1.0@jar", entries[0].coords.get())
    }

    @Test
    fun `same-named file deps fail on collision`() {
        val project = jarjarProject()
        File(dir.toFile(), "libs-a").mkdirs()
        File(dir.toFile(), "libs-b").mkdirs()
        File(dir.toFile(), "libs-a/shared.jar")
            .writeBytes(byteArrayOf(0x50, 0x4B, 5, 6, *ByteArray(18)))
        File(dir.toFile(), "libs-b/shared.jar")
            .writeBytes(byteArrayOf(0x50, 0x4B, 5, 6, *ByteArray(18)))
        project.dependencies.add(
            "include",
            project.files("libs-a/shared.jar", "libs-b/shared.jar"))
        val remapJar = project.tasks.getByName("remapJar")
                as xyz.wagyourtail.unimined.remap.task.RemapJarTask
        val msg = failureMessage { remapJar.nestedJarEntries.get() }
        assertTrue(msg.contains("collision"), msg)
    }

    @Test
    fun `curse and modrinth coords warn but still resolve`() {
        val project = jarjarProject(
            "curse.maven:jei-238222:1.0",
            "maven.modrinth:sodium:1.0")
        val remapJar = project.tasks.getByName("remapJar")
                as xyz.wagyourtail.unimined.remap.task.RemapJarTask
        // diagnostics are warnings — entries must still materialize
        assertEquals(2, remapJar.nestedJarEntries.get().size)
    }
}
