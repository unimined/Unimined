package xyz.wagyourtail.unimined.transform.forge.internal.mcpconfig

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import xyz.wagyourtail.commons.core.logger.JavaLogWrapper
import xyz.wagyourtail.commons.core.logger.Logger
import xyz.wagyourtail.unimined.api.mapping.MappingData
import xyz.wagyourtail.unimined.api.spec.RemapSpec
import xyz.wagyourtail.unimined.remap.spi.PipelineState
import xyz.wagyourtail.unimined.remap.spi.RemapSurface
import xyz.wagyourtail.unimined.remap.spi.StepContext
import xyz.wagyourtail.unimined.transform.step.TransformStepContext
import xyz.wagyourtail.unimined.transform.step.subpipeline.SubpipelineTransformStep
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider
import kotlin.io.path.createDirectories
import kotlin.io.path.outputStream
import kotlin.io.path.writeBytes

/**
 * [SubpipelineTransformStep] through the real `mcpconfig`
 * descriptor format — the registered `McpConfigFormatSource`/
 * `McpConfigFunctionSource` parse a synthetic `config.json` userdev
 * zip: `downloadClient` rides the `client` binding, then a
 * `rename`-type step execs the descriptor's declared tool through
 * `toolResolver` + the real `java` exec path (fake tool jar built
 * in-test).
 */
class SubpipelineMcpConfigTest {

    @TempDir
    lateinit var dir: Path

    private val stepCtx: StepContext by lazy {
        object : StepContext {
        override val surface = RemapSurface.JAR
        override val state: PipelineState by lazy {
            PipelineState(spec.sourceNamespace, null)
        }
        override val spec = object : RemapSpec {
            override val sourceNamespace = "official"
            override val targetNamespace = "named"
            override val classpath: List<Path> = emptyList()
        }
        override val mappings: MappingData? = null
        override val classpath = emptyList<Path>()
        override val workDir = dir.resolve("step-work")
        override val logger: Logger = JavaLogWrapper(
            java.util.logging.Logger.getLogger("mcpconfig-test"))
        }
    }

    private fun ctx() = object : TransformStepContext {
        override val workDir = dir.resolve("transform-work")
        override val stepContext = stepCtx
    }

    private fun userdevZip(config: String): Path {
        val out = dir.resolve("userdev.zip")
        JarOutputStream(out.outputStream()).use { jos ->
            jos.putNextEntry(JarEntry("config.json"))
            jos.write(config.toByteArray())
            jos.closeEntry()
        }
        return out
    }

    /** `fake.Tool --in <p> --out <p>` — byte copy; exits 3 on
     *  `FAILTOOL` marker in argv. */
    private val toolSource = """
        package fake;
        import java.nio.file.*;
        public class Tool {
            public static void main(String[] a) throws Exception {
                for (String s : a) if (s.equals("FAILTOOL")) System.exit(3);
                Files.copy(Paths.get(a[1]), Paths.get(a[3]),
                    StandardCopyOption.REPLACE_EXISTING);
            }
        }
    """.trimIndent()

    private fun compileToolJar(): Path {
        val src = dir.resolve("src/fake/Tool.java").also {
            it.parent.createDirectories()
            it.writeBytes(toolSource.toByteArray())
        }
        val classes = dir.resolve("tool-classes")
        check(ToolProvider.getSystemJavaCompiler().run(
            null, null, null,
            "-d", classes.toString(), src.toString()) == 0)
        // jar it up with a Main-Class manifest so the manifest scan
        // resolves the entry point (descriptor omits `main_class`)
        val jar = dir.resolve("tooljar.jar")
        val mf = java.util.jar.Manifest().apply {
            mainAttributes.putValue("Manifest-Version", "1.0")
            mainAttributes.putValue("Main-Class", "fake.Tool")
        }
        JarOutputStream(jar.outputStream(), mf).use { jos ->
            Files.walk(classes).filter { Files.isRegularFile(it) }
                .forEach { f ->
                    jos.putNextEntry(
                        JarEntry(classes.relativize(f).toString()
                            .replace('\\', '/')))
                    Files.copy(f, jos)
                    jos.closeEntry()
                }
        }
        return jar
    }

    private val config = """
        {
          "spec": 6,
          "data": {"extract": {"client": "data/client/",
                               "server": "data/server/"}},
          "functions": {
            "rename": {
              "version": "fake:tooljar:1",
              "args": ["--in", "{input}", "--out", "{output}"]
            }
          },
          "steps": {"client": [
            {"type": "downloadClient", "name": "download"},
            {"type": "rename", "name": "renamed",
             "input": "{downloadOutput}"}
          ]}
        }
    """.trimIndent()

    @Test
    fun `mcpconfig steps run through the real format and tool exec`() {
        val clientJar = dir.resolve("client.jar")
            .also { it.writeBytes("clean-client".toByteArray()) }
        val toolJar = compileToolJar()
        var resolvedCoord: String? = null
        val step = SubpipelineTransformStep(
            format = "mcpconfig",
            descriptor = { userdevZip(config).toFile() },
            bindings = mapOf("client" to { clientJar.toFile() }),
            toolResolver = { coord ->
                resolvedCoord = coord
                listOf(toolJar)
            },
            side = "client",
        )
        val input = dir.resolve("in.jar")
            .also { it.writeBytes("input".toByteArray()) }
        val out = step.transform(input, ctx())
        assertEquals("fake:tooljar:1", resolvedCoord)
        // canonical output of the last step, content = client jar
        assertTrue(out.fileName.toString() == "renamedOutput.jar",
            "final output should be the rename step's: $out")
        assertEquals("clean-client", String(Files.readAllBytes(out)))
    }

    @Test
    fun `downloadClient alone returns the binding path`() {
        val clientJar = dir.resolve("client.jar")
            .also { it.writeBytes("clean-client".toByteArray()) }
        val oneStep = """
            {
              "spec": 6, "data": {}, "functions": {},
              "steps": {"client": [
                {"type": "downloadClient", "name": "download"}
              ]}
            }
        """.trimIndent()
        val step = SubpipelineTransformStep(
            format = "mcpconfig",
            descriptor = { userdevZip(oneStep).toFile() },
            bindings = mapOf("client" to { clientJar.toFile() }),
            side = "client",
        )
        val input = dir.resolve("in.jar")
            .also { it.writeBytes("input".toByteArray()) }
        val out = step.transform(input, ctx())
        // the binding function serves the bound file itself — not a
        // canonical step output
        assertEquals(clientJar, out)
    }

    @Test
    fun `missing side section errors`() {
        val step = SubpipelineTransformStep(
            format = "mcpconfig",
            descriptor = { userdevZip(config).toFile() },
            bindings = emptyMap(),
            side = "server",
        )
        val input = dir.resolve("in.jar")
            .also { it.writeBytes("input".toByteArray()) }
        val e = org.junit.jupiter.api.assertThrows<IllegalStateException> {
            step.transform(input, ctx())
        }
        assertTrue(e.message!!.contains("no steps for side 'server'"),
            e.message)
    }

}
