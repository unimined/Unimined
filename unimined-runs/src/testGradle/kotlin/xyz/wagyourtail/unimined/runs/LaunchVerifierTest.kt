package xyz.wagyourtail.unimined.runs

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import xyz.wagyourtail.unimined.runs.internal.LaunchVerifier
import java.io.File
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.io.path.outputStream

/**
 * `LaunchVerifier` — the `-Punimined.run.verify` audit must catch the
 * wiring failures a real launch surfaces at class-load: the resolved
 * main class absent from the classpath (the `runFabricProdClient`
 * `KnotClient` CNFE), missing classpath entries, and the
 * `mappings/mappings.tiny` jar a `fabric.gameMappingNamespace` flag
 * requires.
 */
class LaunchVerifierTest {

    private fun jar(path: Path, vararg entries: String): File =
        path.toFile().also { f ->
            f.parentFile?.mkdirs()
            JarOutputStream(f.outputStream()).use { jos ->
                for (e in entries) {
                    jos.putNextEntry(JarEntry(e))
                    jos.write(0)
                    jos.closeEntry()
                }
            }
        }

    @Test
    fun `main class present in a classpath jar passes`() {
        val loader = jar(dir.resolve("loader.jar"),
            "net/fabricmc/loader/impl/launch/knot/KnotClient.class")
        val findings = LaunchVerifier.verify(
            mainClass = "net.fabricmc.loader.impl.launch.knot.KnotClient",
            classpath = listOf(loader),
            jvmArgs = emptyList(),
            javaagents = emptyList(),
            assetIndex = null,
        )
        assertTrue(findings.isEmpty(), "clean plan must pass: $findings")
    }

    @Test
    fun `main class absent from classpath fails — the prod KnotClient case`() {
        val libs = jar(dir.resolve("some-lib.jar"), "com/example/Lib.class")
        val findings = LaunchVerifier.verify(
            mainClass = "net.fabricmc.loader.impl.launch.knot.KnotClient",
            classpath = listOf(libs),
            jvmArgs = emptyList(),
            javaagents = emptyList(),
            assetIndex = null,
        )
        assertTrue(findings.any {
            it.error && "KnotClient" in it.message
        }, "missing main class must fail: $findings")
    }

    @Test
    fun `main class on a directory classpath entry passes`() {
        val classes = dir.resolve("classes").toFile()
        classes.resolve("com/example/Main.class").apply {
            parentFile.mkdirs(); writeText("x")
        }
        val findings = LaunchVerifier.verify(
            mainClass = "com.example.Main",
            classpath = listOf(classes),
            jvmArgs = emptyList(),
            javaagents = emptyList(),
            assetIndex = null,
        )
        assertTrue(findings.isEmpty())
    }

    @Test
    fun `gameMappingNamespace flag without mappings jar fails`() {
        val loader = jar(dir.resolve("loader.jar"),
            "net/fabricmc/loader/impl/launch/knot/KnotClient.class")
        val findings = LaunchVerifier.verify(
            mainClass = "net.fabricmc.loader.impl.launch.knot.KnotClient",
            classpath = listOf(loader),
            jvmArgs = listOf("-Dfabric.gameMappingNamespace=intermediary"),
            javaagents = emptyList(),
            assetIndex = null,
        )
        assertTrue(findings.any {
            it.error && "mappings.tiny" in it.message
        }, "prod-namespace flag without mappings jar must fail: $findings")

        val withMappings = jar(dir.resolve("mappings.jar"),
            "mappings/mappings.tiny")
        val ok = LaunchVerifier.verify(
            mainClass = "net.fabricmc.loader.impl.launch.knot.KnotClient",
            classpath = listOf(loader, withMappings),
            jvmArgs = listOf("-Dfabric.gameMappingNamespace=intermediary"),
            javaagents = emptyList(),
            assetIndex = null,
        )
        assertTrue(ok.isEmpty(), "mappings jar present must pass: $ok")
    }

    @Test
    fun `missing classpath entry and agent are reported`() {
        val main = jar(dir.resolve("m.jar"), "com/example/Main.class")
        val findings = LaunchVerifier.verify(
            mainClass = "com.example.Main",
            classpath = listOf(main, dir.resolve("ghost.jar").toFile()),
            jvmArgs = emptyList(),
            javaagents = listOf(dir.resolve("agent.jar").toFile()),
            assetIndex = null,
        )
        assertTrue(findings.any { it.error && "ghost.jar" in it.message })
        assertTrue(findings.any { it.error && "agent.jar" in it.message })
    }

    @Test
    fun `empty classpath and blank main are findings`() {
        val findings = LaunchVerifier.verify(
            mainClass = null,
            classpath = emptyList(),
            jvmArgs = emptyList(),
            javaagents = emptyList(),
            assetIndex = null,
        )
        assertEquals(2, findings.count { it.error })
    }

    @TempDir lateinit var dir: Path
}
