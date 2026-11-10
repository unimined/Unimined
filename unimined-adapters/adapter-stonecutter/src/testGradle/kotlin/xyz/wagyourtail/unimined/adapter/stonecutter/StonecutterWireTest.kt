package xyz.wagyourtail.unimined.adapter.stonecutter

import dev.kikugie.stonecutter.build.StonecutterBuildExtension
import dev.kikugie.stonecutter.data.StonecutterProject
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import xyz.wagyourtail.unimined.minecraft.MinecraftExtension
import java.lang.reflect.Proxy

/** The chiseled project's version conventions onto every env;
 *  an explicit `version` still wins. */
class StonecutterWireTest {

    private fun fakeStonecutter(version: String): StonecutterBuildExtension =
        Proxy.newProxyInstance(
            javaClass.classLoader,
            arrayOf(StonecutterBuildExtension::class.java)
        ) { _, method, _ ->
            when (method.name) {
                "getCurrent" -> StonecutterProject("main", version)
                "hashCode" -> 0
                "toString" -> "FakeStonecutter($version)"
                else -> throw UnsupportedOperationException(
                    "unneeded in this test: ${method.name}")
            }
        } as StonecutterBuildExtension

    private fun project() = ProjectBuilder.builder().build().also {
        it.pluginManager.apply(
            xyz.wagyourtail.unimined.minecraft.GradlePlugin::class.java)
    }

    @Test
    fun `env version conventions to the chiseled project version`() {
        val p = project()
        wireFromStonecutter(p, fakeStonecutter("1.20.1"))
        val minecraft = p.extensions.getByType(MinecraftExtension::class.java)
        assertEquals("1.20.1", minecraft.main.version.get())
    }

    @Test
    fun `explicit version wins over the stonecutter convention`() {
        val p = project()
        wireFromStonecutter(p, fakeStonecutter("1.20.1"))
        val minecraft = p.extensions.getByType(MinecraftExtension::class.java)
        minecraft.version.set("1.20.4")
        assertEquals("1.20.4", minecraft.main.version.get())
    }

}
