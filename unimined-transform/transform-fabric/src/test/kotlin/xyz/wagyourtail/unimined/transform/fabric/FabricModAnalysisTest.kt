package xyz.wagyourtail.unimined.transform.fabric

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import xyz.wagyourtail.unimined.transform.fabric.internal.FabricModAnalysis
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.io.path.outputStream

/**
 * Unit coverage for the loader jar metadata reader — `fabric-installer.json`
 * is the loader's only declaration of its runtime libraries (the published
 * pom is intentionally empty).
 */
class FabricModAnalysisTest {

    @TempDir
    lateinit var dir: Path

    private fun jarWithInstaller(json: String?): Path {
        val jar = dir.resolve("fabric-loader-test.jar")
        JarOutputStream(jar.outputStream()).use { out ->
            if (json != null) {
                out.putNextEntry(JarEntry("fabric-installer.json"))
                out.write(json.toByteArray())
                out.closeEntry()
            }
        }
        return jar
    }

    @Test
    fun `loaderLibraries reads common and server sections`() {
        val jar = jarWithInstaller(
            """
            {
              "libraries": {
                "common": [
                  {"name": "org.ow2.asm:asm:9.6"},
                  {"name": "net.fabricmc:sponge-mixin:0.15.0+mixin.0.8.7"}
                ],
                "server": [
                  {"name": "net.fabricmc:tiny-remapper:0.10.4"}
                ],
                "client": []
              }
            }
            """.trimIndent()
        )
        assertEquals(
            listOf(
                "org.ow2.asm:asm:9.6",
                "net.fabricmc:sponge-mixin:0.15.0+mixin.0.8.7",
                "net.fabricmc:tiny-remapper:0.10.4"
            ),
            FabricModAnalysis.loaderLibraries(jar)
        )
    }

    @Test
    fun `loaderLibraries returns empty for jar without installer json`() {
        assertEquals(emptyList<String>(), FabricModAnalysis.loaderLibraries(jarWithInstaller(null)))
    }

    @Test
    fun `loaderLibraries returns empty for malformed installer json`() {
        assertEquals(
            emptyList<String>(),
            FabricModAnalysis.loaderLibraries(jarWithInstaller("not json"))
        )
    }

}
