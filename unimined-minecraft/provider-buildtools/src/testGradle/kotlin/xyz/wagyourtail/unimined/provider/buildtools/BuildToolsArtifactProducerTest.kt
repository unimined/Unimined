package xyz.wagyourtail.unimined.provider.buildtools

import org.gradle.api.GradleException
import org.gradle.api.logging.Logging
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import xyz.wagyourtail.unimined.api.spec.Side
import xyz.wagyourtail.unimined.api.util.uniminedLogger
import xyz.wagyourtail.unimined.minecraft.internal.RuntimeLoaderSpec
import xyz.wagyourtail.unimined.minecraft.internal.RuntimeProductSpec
import xyz.wagyourtail.unimined.minecraft.internal.RuntimeVersionSpec
import xyz.wagyourtail.unimined.minecraft.spi.ArtifactKey
import xyz.wagyourtail.unimined.minecraft.spi.ArtifactProductionRequest
import xyz.wagyourtail.unimined.minecraft.spi.ProductArtifactProducerRegistry
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import javax.tools.ToolProvider

/** `buildtools` artifact producer — `produce()` runs Spigot's
 *  BuildTools inside the raw-artifact transform via `ProcessBuilder`
 *  (no `ExecOperations` in transform isolation). The full-run tests
 *  below use a compiled fake `BuildTools.jar` whose main writes the
 *  maven-module output the runner locates; every download channel is
 *  pre-staged so nothing reaches the network. */
class BuildToolsArtifactProducerTest {

    @TempDir
    lateinit var dir: Path

    private fun spec(loader: String?, mcVersion: String = "1.20.4") =
        RuntimeProductSpec(
            environmentId = "main",
            sourceSetName = "main",
            version = RuntimeVersionSpec(mcVersion),
            side = Side.SERVER,
            loader = loader?.let { RuntimeLoaderSpec(it, "1.0") })

    private fun request(
        cacheDir: Path, key: ArtifactKey = ArtifactKey.SERVER
    ) = ArtifactProductionRequest(
        cacheDir = cacheDir,
        artifactKey = key,
        offline = true,
        logger = uniminedLogger(
            Logging.getLogger(BuildToolsArtifactProducer::class.java))
    )

    @Test
    fun `claims bukkit-family loaders only`() {
        val producer = BuildToolsArtifactProducer()
        assertTrue(producer.supports(spec("craftbukkit")))
        assertTrue(producer.supports(spec("spigot")))
        assertTrue(!producer.supports(spec("fabric")))
        assertTrue(!producer.supports(spec(null)))

        // priority 1 outranks native's catch-all (0) on its loaders
        val registry = ProductArtifactProducerRegistry()
        assertTrue(registry.select(spec("spigot"))
                is BuildToolsArtifactProducer)
        assertTrue(registry.select(spec("craftbukkit"), "buildtools")
                is BuildToolsArtifactProducer)
    }

    @Test
    fun `client key fails — bukkit-family is server-only`() {
        val e = assertThrows(GradleException::class.java) {
            BuildToolsArtifactProducer().produce(
                spec("spigot"),
                request(dir.resolve("cache"), ArtifactKey.CLIENT))
        }
        assertTrue("no client artifact" in e.message!!)
    }

    @Test
    fun `already-built output returns without running BuildTools`() {
        val cache = dir.resolve("cache")
        // a prior build's maven-module output — `existingOutput`
        // short-circuits before the BuildTools.jar download + exec
        val out = cache.resolve(
            "buildtools/spigot/spigot-1.20.4/Spigot/target/" +
                    "spigot-1.20.4-R0.1-SNAPSHOT.jar")
        Files.createDirectories(out.parent)
        Files.write(out, byteArrayOf(9, 9, 9))
        val produced = BuildToolsArtifactProducer().produce(
            spec("spigot"), request(cache))
        assertEquals(out, produced)
        assertEquals(byteArrayOf(9, 9, 9).toList(),
            Files.readAllBytes(produced).toList())
    }

    /** Compile `BtMain` — a BuildTools stand-in: records its arg line,
     *  exits 3 when the work dir carries `fail-next`, otherwise writes
     *  the `<Module>/target/<target>-<rev>-R0.1-SNAPSHOT.jar` output
     *  real BuildTools produces. Returns the jar bytes with a
     *  `Main-Class` manifest. */
    private fun fakeBuildToolsJar(): ByteArray {
        val src = dir.resolve("BtMain.java")
        Files.writeString(src, """
            import java.nio.file.*;
            public class BtMain {
                public static void main(String[] args) throws Exception {
                    Files.write(Path.of("args.txt"),
                        String.join(" ", args).getBytes());
                    if (Files.exists(Path.of("fail-next"))) {
                        System.exit(3);
                    }
                    String rev = "0"; String target = "spigot";
                    for (int i = 0; i + 1 < args.length; i++) {
                        if (args[i].equals("--rev")) rev = args[i + 1];
                        if (args[i].equals("--compile"))
                            target = args[i + 1].toLowerCase();
                    }
                    Path out = Path.of("Mod/target/" + target + "-"
                        + rev + "-R0.1-SNAPSHOT.jar");
                    Files.createDirectories(out.getParent());
                    Files.write(out, new byte[]{7, 7, 7});
                }
            }
        """.trimIndent())
        val classes = dir.resolve("bt-classes")
        Files.createDirectories(classes)
        val rc = ToolProvider.getSystemJavaCompiler()
            .run(null, null, null, "-d", classes.toString(), src.toString())
        assertEquals(0, rc)
        val bytes = java.io.ByteArrayOutputStream()
        JarOutputStream(bytes, Manifest().apply {
            mainAttributes.putValue("Manifest-Version", "1.0")
            mainAttributes.putValue("Main-Class", "BtMain")
        }).use { jar ->
            jar.putNextEntry(JarEntry("BtMain.class"))
            Files.copy(classes.resolve("BtMain.class"), jar)
            jar.closeEntry()
        }
        return bytes.toByteArray()
    }

    /** Stage everything `produce` pulls off the network: the
     *  BuildTools jar itself and the BuildData `javaVersions` doc
     *  ([52, 78] = Java 8–34 — always in range for the build JVM). */
    private fun stage(cache: Path, rev: String) {
        val tools = cache.resolve("buildtools/BuildTools.jar")
        Files.createDirectories(tools.parent)
        Files.write(tools, fakeBuildToolsJar())
        val info = cache.resolve("spigot/$rev/build-info.json")
        Files.createDirectories(info.parent)
        Files.writeString(info, """{"javaVersions":[52,78]}""")
    }

    @Test
    fun `produce runs the fake BuildTools through ProcessBuilder`() {
        val cache = dir.resolve("cache")
        stage(cache, "1.20.4")
        val out = BuildToolsArtifactProducer().produce(
            spec("spigot"), request(cache))
        assertEquals("spigot-1.20.4-R0.1-SNAPSHOT.jar",
            out.fileName.toString())
        assertEquals(byteArrayOf(7, 7, 7).toList(),
            Files.readAllBytes(out).toList())
        // the subprocess ran in the per-rev workdir and saw the
        // `--rev/--compile` arg line BuildToolsExec builds
        val work = cache.resolve("buildtools/spigot/spigot-1.20.4")
        assertTrue(Files.readString(work.resolve("args.txt"))
            .contains("--rev 1.20.4 --compile SPIGOT"))
        // non-zero output is captured to the workdir log
        assertTrue(Files.exists(work.resolve("buildtools.log")))
    }

    @Test
    fun `craftbukkit compiles the CRAFTBUKKIT module`() {
        val cache = dir.resolve("cache")
        stage(cache, "1.21.1")
        val out = BuildToolsArtifactProducer().produce(
            spec("craftbukkit", "1.21.1"), request(cache))
        assertEquals("craftbukkit-1.21.1-R0.1-SNAPSHOT.jar",
            out.fileName.toString())
        val work = cache.resolve(
            "buildtools/craftbukkit/craftbukkit-1.21.1")
        assertTrue(Files.readString(work.resolve("args.txt"))
            .contains("--compile CRAFTBUKKIT"))
    }

    @Test
    fun `build failure names the captured log`() {
        val cache = dir.resolve("cache")
        stage(cache, "9.9.9")
        val work = cache.resolve("buildtools/spigot/spigot-9.9.9")
        Files.createDirectories(work)
        Files.write(work.resolve("fail-next"), byteArrayOf(0))
        val e = assertThrows(GradleException::class.java) {
            BuildToolsArtifactProducer().produce(
                spec("spigot", "9.9.9"), request(cache))
        }
        assertTrue("exited 3" in e.message!!)
        assertTrue("buildtools.log" in e.message!!)
    }

}
