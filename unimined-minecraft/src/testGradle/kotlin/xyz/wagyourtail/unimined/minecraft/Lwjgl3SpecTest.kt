package xyz.wagyourtail.unimined.minecraft

import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import xyz.wagyourtail.unimined.minecraft.fakes.FakeProvider
import java.nio.file.Path

class Lwjgl3SpecTest {

    @TempDir
    lateinit var dir: Path

    private fun project() = ProjectBuilder.builder()
        .withProjectDir(dir.toFile())
        .build()
        .also {
            it.plugins.apply("java")
            it.plugins.apply(GradlePlugin::class.java)
        }

    @BeforeEach
    fun resetFake() = FakeProvider.reset()

    @Test
    fun `lwjgl3 verb enables the spec and drops lwjgl2 coords`() {
        val ext = project().extensions.getByType(MinecraftExtension::class.java)
        val env = ext.main
        assertFalse(env.lwjgl3.enabled.get())
        env.lwjgl3 { it.version.set("3.3.3") }
        assertTrue(env.lwjgl3.enabled.get())
        val drop = env.libraryCoordFilters.single {
            it("org.lwjgl.lwjgl:lwjgl:2.9.1") == null
        }
        assertNull(drop("org.lwjgl.lwjgl:lwjgl_util:2.9.1"))
        assertNull(drop("org.lwjgl.lwjgl:lwjgl-platform:2.9.1:natives-linux"))
        assertEquals("org.lwjgl:lwjgl:3.3.3", drop("org.lwjgl:lwjgl:3.3.3"))
        assertEquals("net.java.jinput:jinput:2.0.5", drop("net.java.jinput:jinput:2.0.5"))
    }

    @Test
    fun `lwjgl3 disabled inside the block adds no coord drop`() {
        val env = project().extensions
            .getByType(MinecraftExtension::class.java).main
        env.lwjgl3 { it.enabled.set(false) }
        assertFalse(env.lwjgl3.enabled.get())
        // the swap gates on the final enabled flag — no installed
        // filter drops lwjgl2 coords while disabled
        assertTrue(env.libraryCoordFilters.all {
            it("org.lwjgl.lwjgl:lwjgl:2.9.1") != null
        })
        // re-invoking re-enables — the same filter now drops
        env.lwjgl3 {}
        assertTrue(env.lwjgl3.enabled.get())
        assertNotNull(env.libraryCoordFilters.firstOrNull {
            it("org.lwjgl.lwjgl:lwjgl:2.9.1") == null
        })
    }

    @Test
    fun `jarCoords carries core plus all bindings`() {
        val env = project().extensions
            .getByType(MinecraftExtension::class.java).main
        env.lwjgl3 {
            it.version.set("3.3.3")
            it.bindings.add("stb")
            it.nativeslessBindings.add("harfbuzz")
        }
        assertEquals(
            listOf(
                "org.lwjgl:lwjgl:3.3.3",
                "org.lwjgl:lwjgl-stb:3.3.3",
                "org.lwjgl:lwjgl-harfbuzz:3.3.3",
            ), env.lwjgl3.jarCoords()
        )
        // natives cover core + native-bearing bindings only
        assertEquals(
            listOf(
                "org.lwjgl:lwjgl-lwjgl:3.3.3:natives-linux",
                "org.lwjgl:lwjgl-stb:3.3.3:natives-linux",
            ), env.lwjgl3.nativesCoords("natives-linux")
        )
    }

    @Test
    fun `nativesClassifier picks host os and arch`() {
        val env = project().extensions
            .getByType(MinecraftExtension::class.java).main
        with(env.lwjgl3) {
            assertEquals("natives-windows", nativesClassifier("windows 11", "amd64"))
            assertEquals("natives-windows-x86", nativesClassifier("windows 11", "x86"))
            assertEquals("natives-windows-arm64", nativesClassifier("windows 11", "aarch64"))
            assertEquals("natives-macos", nativesClassifier("mac os x", "x86_64"))
            assertEquals("natives-macos-arm64", nativesClassifier("mac os x", "aarch64"))
            assertEquals("natives-linux", nativesClassifier("linux", "amd64"))
            assertEquals("natives-linux-arm64", nativesClassifier("linux", "aarch64"))
        }
    }
}
