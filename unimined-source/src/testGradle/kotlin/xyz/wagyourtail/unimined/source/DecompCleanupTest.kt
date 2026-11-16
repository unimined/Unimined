package xyz.wagyourtail.unimined.source

import org.gradle.api.internal.project.ProjectInternal
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import xyz.wagyourtail.unimined.minecraft.MinecraftExtension
import xyz.wagyourtail.unimined.source.internal.GlConstantFixer
import xyz.wagyourtail.unimined.source.task.DecompCleanupTask
import java.nio.file.Files
import java.nio.file.Path

/**
 * `cleanupSources`/`cleanup<Env>Sources` wiring + the
 * `gl_constants.json`-backed fixer (RFG
 * `applyDecompilerCleanupToMain`/`GLConstantFixer` parity).
 */
class DecompCleanupTest {

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
            project.plugins.apply("xyz.wagyourtail.unimined.source")
        }

    private fun evaluate(project: org.gradle.api.Project) =
        (project as ProjectInternal).evaluate()

    @Test
    fun `cleanupSources task registered per environment`() {
        val project = project()
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        ext.sourceSet("main") { it.version.set("1.12.2") }
        ext.sourceSet("server") { it.version.set("1.12.2") }
        evaluate(project)

        assertTrue(project.tasks.getByName("cleanupSources")
            is DecompCleanupTask)
        assertTrue(project.tasks.getByName("cleanupServerSources")
            is DecompCleanupTask)
    }

    @Test
    fun `raw gl literal becomes named constant`() {
        val source = """
            import org.lwjgl.opengl.GL11;
            class Render {
                void draw() {
                    GL11.glBegin(7);
                    GL11.glBlendFunc(770, 771);
                }
            }
        """.trimIndent()
        val out = GlConstantFixer.fixOGL(source)
        assertTrue(out.contains("GL11.glBegin(GL11.GL_QUADS)"), out)
        assertTrue(out.contains(
            "GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA)"
        ), out)
    }

    @Test
    fun `cross-package constants pull in their import`() {
        val source = """
            import org.lwjgl.opengl.GL11;
            import org.lwjgl.opengl.GL15;
            class Buf {
                void bind() {
                    GL15.glBindBuffer(34962);
                }
            }
        """.trimIndent()
        val out = GlConstantFixer.fixOGL(source)
        assertTrue(out.contains(
            "GL15.glBindBuffer(ARBVertexBufferObject.GL_ARRAY_BUFFER_ARB)"
        ), out)
        assertTrue(out.contains(
            "import org.lwjgl.opengl.ARBVertexBufferObject;"
        ), out)
    }

    @Test
    fun `files without lwjgl imports and unmapped literals stay untouched`() {
        val plain = "class Foo { int x = 7; }"
        assertEquals(plain, GlConstantFixer.fixOGL(plain))
        val unmapped = """
            import org.lwjgl.opengl.GL11;
            class R { void d() { GL11.glBegin(999); } }
        """.trimIndent()
        assertEquals(unmapped, GlConstantFixer.fixOGL(unmapped))
    }

    @Test
    fun `task rewrites matching java sources in place`() {
        val project = project()
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        ext.sourceSet("main") { it.version.set("1.12.2") }
        evaluate(project)

        val srcDir = dir.resolve("src/main/java").also {
            Files.createDirectories(it)
        }
        val file = srcDir.resolve("Render.java")
        Files.writeString(file, """
            import org.lwjgl.opengl.GL11;
            class Render { void d() { GL11.glBegin(7); } }
        """.trimIndent())
        val untouched = srcDir.resolve("Plain.java")
        Files.writeString(untouched, "class Plain { int x = 7; }")

        val task = project.tasks.getByName("cleanupSources")
            as DecompCleanupTask
        // env wiring also injects provider-backed generated-source
        // dirs into sourceSet.java; the exec path is exercised with
        // just the user dir here
        task.sourceDirs.setFrom(srcDir)
        task.buildDir.set(dir.resolve("build").toFile())
        task.cleanup()

        assertTrue(Files.readString(file)
            .contains("GL11.glBegin(GL11.GL_QUADS)"))
        assertEquals("class Plain { int x = 7; }",
            Files.readString(untouched))
    }

}
