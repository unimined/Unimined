package xyz.wagyourtail.unimined.minecraft

import groovy.lang.GroovyShell
import org.gradle.api.Project
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import xyz.wagyourtail.unimined.api.environment.EnvironmentSection
import java.nio.file.Path

/**
 * The env-scoped `dependencies { }` block — verb→bucket routing for
 * `main` and named envs, the materialize-on-use `api` semantics, the
 * java-plugin-late deferral, Groovy closure dispatch, and
 * `combineWith(DEPENDENCIES)` propagation of the mod buckets.
 */
class EnvironmentDependencySpecTest {

    @TempDir
    lateinit var dir: Path

    private fun project(applyJava: Boolean = true) = ProjectBuilder.builder()
        .withProjectDir(dir.toFile())
        .build()
        .also {
            if (applyJava) it.plugins.apply("java")
            it.plugins.apply(GradlePlugin::class.java)
        }

    private fun evaluate(project: Project) =
        (project as org.gradle.api.internal.project.ProjectInternal).evaluate()

    private fun minecraft(project: Project) =
        project.extensions.getByType(MinecraftExtension::class.java)

    private fun depCoords(project: Project, confName: String): List<String> =
        project.configurations.getByName(confName).dependencies.map {
            "${it.group}:${it.name}:${it.version}"
        }

    @Test
    fun `main env verbs route to the unprefixed buckets`() {
        val project = project()
        val env = minecraft(project).main

        env.dependencies {
            it.implementation("com.example:impl:1.0")
            it.compileOnly("com.example:compile:1.0")
            it.runtimeOnly("com.example:runtime:1.0")
            it.modImplementation("com.example:modimpl:1.0")
            it.modCompileOnly("com.example:modcompile:1.0")
            it.modRuntimeOnly("com.example:modruntime:1.0")
            it.include("com.example:embed:1.0")
        }

        assertEquals(listOf("com.example:impl:1.0"), depCoords(project, "implementation"))
        assertEquals(listOf("com.example:compile:1.0"), depCoords(project, "compileOnly"))
        assertEquals(listOf("com.example:runtime:1.0"), depCoords(project, "runtimeOnly"))
        assertEquals(listOf("com.example:modimpl:1.0"), depCoords(project, "modImplementation"))
        assertEquals(listOf("com.example:modcompile:1.0"), depCoords(project, "modCompileOnly"))
        assertEquals(listOf("com.example:modruntime:1.0"), depCoords(project, "modRuntimeOnly"))
        assertEquals(listOf("com.example:embed:1.0"), depCoords(project, "include"))
    }

    @Test
    fun `named env verbs route to the prefixed buckets`() {
        val project = project()
        val ext = minecraft(project)
        val fabric = ext.sourceSet("fabric") {
            it.dependencies { deps ->
                deps.implementation("com.example:impl:1.0")
                deps.compileOnly("com.example:compile:1.0")
                deps.runtimeOnly("com.example:runtime:1.0")
                deps.modImplementation("com.example:modimpl:1.0")
                deps.modCompileOnly("com.example:modcompile:1.0")
                deps.modRuntimeOnly("com.example:modruntime:1.0")
                deps.include("com.example:embed:1.0")
            }
        }
        assertEquals("fabric", fabric.environmentId)

        assertEquals(listOf("com.example:impl:1.0"), depCoords(project, "fabricImplementation"))
        assertEquals(listOf("com.example:compile:1.0"), depCoords(project, "fabricCompileOnly"))
        assertEquals(listOf("com.example:runtime:1.0"), depCoords(project, "fabricRuntimeOnly"))
        assertEquals(listOf("com.example:modimpl:1.0"), depCoords(project, "fabricModImplementation"))
        assertEquals(listOf("com.example:modcompile:1.0"), depCoords(project, "fabricModCompileOnly"))
        assertEquals(listOf("com.example:modruntime:1.0"), depCoords(project, "fabricModRuntimeOnly"))
        assertEquals(listOf("com.example:embed:1.0"), depCoords(project, "fabricInclude"))
    }

    @Test
    fun `mod buckets exist even before java creates the source set`() {
        // the mod buckets are unimined-owned — declaring into them must
        // not depend on java-plugin ordering
        val project = project(applyJava = false)
        minecraft(project).main.dependencies {
            it.modCompileOnly("com.example:modcompile:1.0")
        }
        assertEquals(listOf("com.example:modcompile:1.0"), depCoords(project, "modCompileOnly"))
    }

    @Test
    fun `api materializes the bucket with java-library semantics on main`() {
        val project = project()
        // plain `java` — no `api` bucket exists yet
        assertTrue(project.configurations.findByName("api") == null)

        minecraft(project).main.dependencies {
            it.api("com.example:apidep:1.0")
        }

        val api = project.configurations.getByName("api")
        assertFalse(api.isCanBeConsumed)
        assertFalse(api.isCanBeResolved)
        assertEquals(listOf("com.example:apidep:1.0"), depCoords(project, "api"))
        assertTrue(
            api in project.configurations.getByName("implementation").extendsFrom,
            "implementation must extend api so api deps are compile+runtime visible")
    }

    @Test
    fun `api materializes the bucket for custom source sets`() {
        val project = project()
        // Gradle creates no `<env>Api` even under java-library
        assertTrue(project.configurations.findByName("fabricApi") == null)

        minecraft(project).sourceSet("fabric") {
            it.dependencies { deps ->
                deps.api("com.example:apidep:1.0")
            }
        }

        val api = project.configurations.getByName("fabricApi")
        assertEquals(listOf("com.example:apidep:1.0"), depCoords(project, "fabricApi"))
        assertTrue(
            api in project.configurations.getByName("fabricImplementation").extendsFrom,
            "fabricImplementation must extend fabricApi")
    }

    @Test
    fun `api does not double-wire when java-library already provides it`() {
        val project = project()
        project.plugins.apply("java-library")

        minecraft(project).main.dependencies {
            it.api("com.example:apidep:1.0")
        }

        val api = project.configurations.getByName("api")
        val impl = project.configurations.getByName("implementation")
        assertEquals(
            1, impl.extendsFrom.count { it === api },
            "java-library's own wiring must be reused, not duplicated")
        assertEquals(listOf("com.example:apidep:1.0"), depCoords(project, "api"))
    }

    @Test
    fun `java buckets defer when java is applied after the env block`() {
        val project = project(applyJava = false)
        val ext = minecraft(project)

        // no java yet — `implementation`/`fabricImplementation` do not
        // exist; the verbs must not fail, the declarations defer
        ext.main.dependencies {
            it.implementation("com.example:impl:1.0")
        }
        ext.sourceSet("fabric") {
            it.dependencies { deps ->
                deps.implementation("com.example:fimpl:1.0")
            }
        }
        assertTrue(project.configurations.findByName("implementation") == null)

        project.plugins.apply("java")

        assertEquals(listOf("com.example:impl:1.0"), depCoords(project, "implementation"))
        assertEquals(listOf("com.example:fimpl:1.0"), depCoords(project, "fabricImplementation"))
    }

    @Test
    fun `deferred and eager adds do not double-add`() {
        val project = project(applyJava = false)
        val ext = minecraft(project)
        ext.main.dependencies {
            it.implementation("com.example:early:1.0")
        }
        project.plugins.apply("java")
        ext.main.dependencies {
            it.implementation("com.example:late:1.0")
        }
        assertEquals(
            listOf("com.example:early:1.0", "com.example:late:1.0"),
            depCoords(project, "implementation"))
    }

    @Test
    fun `groovy twin dispatches env and nested verb delegation`() {
        val project = project()
        val ext = minecraft(project)
        val shell = GroovyShell()
        shell.setVariable("env", ext.main)
        shell.setVariable("ext", ext)

        // env-scoped block — unqualified verbs resolve on the spec
        // delegate (DELEGATE_FIRST), not the script
        shell.evaluate(
            "env.dependencies {\n" +
                    "  implementation 'com.example:gimpl:1.0'\n" +
                    "  modCompileOnly 'com.example:gmodcompile:1.0'\n" +
                    "  include 'com.example:gembed:1.0'\n" +
                    "}"
        )
        // extension facade delegates to main
        shell.evaluate(
            "ext.dependencies {\n" +
                    "  modImplementation 'com.example:gmodimpl:1.0'\n" +
                    "}"
        )

        assertEquals(listOf("com.example:gimpl:1.0"), depCoords(project, "implementation"))
        assertEquals(listOf("com.example:gmodcompile:1.0"), depCoords(project, "modCompileOnly"))
        assertEquals(listOf("com.example:gembed:1.0"), depCoords(project, "include"))
        assertEquals(listOf("com.example:gmodimpl:1.0"), depCoords(project, "modImplementation"))
    }

    @Test
    fun `groovy named-env block dispatches inside sourceSet closures`() {
        val project = project()
        val ext = minecraft(project)
        val shell = GroovyShell()
        shell.setVariable("ext", ext)
        shell.evaluate(
            "ext.sourceSet('fabric') {\n" +
                    "  dependencies {\n" +
                    "    modCompileOnly 'com.example:gmodcompile:1.0'\n" +
                    "  }\n" +
                    "}"
        )
        assertEquals(
            listOf("com.example:gmodcompile:1.0"),
            depCoords(project, "fabricModCompileOnly"))
    }

    @Test
    fun `file and project-shaped notation passes through`() {
        val project = project()
        val jar = dir.resolve("local.jar").toFile().apply { writeBytes(byteArrayOf(1, 2, 3)) }
        minecraft(project).main.dependencies {
            it.modCompileOnly(project.files(jar))
        }
        val files = project.configurations.getByName("modCompileOnly").dependencies
        assertEquals(1, files.size)
    }

    @Test
    fun `combineWith DEPENDENCIES propagates the modCompileOnly bucket`() {
        val project = project()
        val ext = minecraft(project)
        ext.main.version.set("1.0")
        ext.main.dependencies {
            it.modCompileOnly("com.example:modcompile:1.0")
        }
        ext.sourceSet("child") {
            it.combineWith("main", EnvironmentSection.DEPENDENCIES)
        }

        evaluate(project)

        assertTrue(
            project.configurations.getByName("modCompileOnly") in
                    project.configurations.getByName("childModCompileOnly").extendsFrom,
            "childModCompileOnly should extend modCompileOnly")
        assertNotNull(project.configurations.findByName("childModCompileOnly"))
    }

}
