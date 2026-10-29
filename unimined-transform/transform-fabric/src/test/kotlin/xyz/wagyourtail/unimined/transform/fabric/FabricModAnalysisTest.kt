package xyz.wagyourtail.unimined.transform.fabric

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import xyz.wagyourtail.unimined.transform.fabric.internal.FabricModAnalysis
import java.nio.file.Files
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

    private fun modJar(fmj: String, entries: Map<String, String> = emptyMap()): Path {
        val jar = dir.resolve("mod-${System.nanoTime()}.jar")
        JarOutputStream(jar.outputStream()).use { out ->
            out.putNextEntry(JarEntry("fabric.mod.json"))
            out.write(fmj.toByteArray())
            out.closeEntry()
            for ((path, content) in entries) {
                out.putNextEntry(JarEntry(path))
                out.write(content.toByteArray())
                out.closeEntry()
            }
        }
        return jar
    }

    @Test
    fun `extractClassTweakers copies the declared accessWidener out of the mod jar`() {
        val jar = modJar(
            """{"accessWidener": "testmod.accesswidener"}""",
            mapOf("testmod.accesswidener" to "accessWidener\tv2\tnamed\ttransitive-accessible\tclass\ta/b/C")
        )
        val extracted = FabricModAnalysis.extractClassTweakers(jar, dir.resolve("ct-cache"))
        assertEquals(
            "accessWidener\tv2\tnamed\ttransitive-accessible\tclass\ta/b/C",
            Files.readString(extracted.single())
        )
    }

    @Test
    fun `extractClassTweakers returns empty without declared tweakers`() {
        assertEquals(
            emptyList<Path>(),
            FabricModAnalysis.extractClassTweakers(modJar("{}"), dir.resolve("ct-cache"))
        )
    }

    @Test
    fun `extractClassTweakers skips missing declared entries`() {
        assertEquals(
            emptyList<Path>(),
            FabricModAnalysis.extractClassTweakers(
                modJar("""{"accessWidener": "missing.accesswidener"}"""),
                dir.resolve("ct-cache")
            )
        )
    }

    @Test
    fun `classTweakers reads the fmj v2 string form`() {
        val jar = modJar("""{"classTweakers": "mod.classTweaker"}""")
        assertEquals(
            mapOf("mod.classTweaker" to "*"),
            FabricModAnalysis.classTweakers(jar)
        )
    }

    @Test
    fun `classTweakers reads the fmj v2 array form with environments`() {
        val jar = modJar(
            """
            {
              "classTweakers": [
                "a.classTweaker",
                {"config": "b.classTweaker"},
                {"config": "c.classTweaker", "environment": "client"}
              ]
            }
            """.trimIndent()
        )
        assertEquals(
            mapOf(
                "a.classTweaker" to "*",
                "b.classTweaker" to "*",
                "c.classTweaker" to "client"
            ),
            FabricModAnalysis.classTweakers(jar)
        )
    }

    @Test
    fun `classTweakers merges v1 accessWidener and v2 classTweakers`() {
        val jar = modJar(
            """{"accessWidener": "mod.accesswidener", "classTweakers": "mod.classTweaker"}"""
        )
        assertEquals(
            mapOf("mod.accesswidener" to "*", "mod.classTweaker" to "*"),
            FabricModAnalysis.classTweakers(jar)
        )
    }

}
