package xyz.wagyourtail.unimined.transform.step.subpipeline

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.Attributes
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import java.util.zip.ZipFile
import javax.tools.ToolProvider
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.outputStream
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText

/**
 * [SubpipelineExecutor] over hand-built [DeclaredSubpipeline]s —
 * interpolation (`{input}`, `{xVar}`, `{stepOutput}`, `{output}`),
 * registry-function dispatch, the descriptor tool-table fallback
 * through the injected `toolExec` port, the real [ProcessExec]
 * javaexec path, `untilStep` early stop, and the existing-output
 * skip gate.
 */
class SubpipelineExecutorTest {

    @TempDir
    lateinit var dir: Path

    private val descriptor by lazy {
        dir.resolve("descriptor.json").toFile()
            .also { it.writeText("{}") }
    }

    private fun executor(
        pipeline: DeclaredSubpipeline,
        functions: Map<String, PipelineFunction> = emptyMap(),
        bindings: Map<String, () -> java.io.File> = emptyMap(),
        toolResolver: (String) -> List<Path> = { emptyList() },
        listBindings: Map<String, () -> List<java.io.File>> = emptyMap(),
        toolExec: ((DeclaredTool, List<String>, List<String>, List<Path>) -> Unit)? = null,
    ) = SubpipelineExecutor(
        pipeline, object : PipelineDescriptorFormat {
            override val name = "test"
            override fun parse(descriptor: java.io.File, ctx: FormatContext) =
                pipeline
        }, functions, bindings, toolResolver, listBindings, toolExec)

    private fun writeFn(
        name: String,
        body: (SubpipelineContext) -> String = { "x" }
    ) = object : PipelineFunction {
        override val name = name
        override fun run(step: SubStep, ctx: SubpipelineContext): Path {
            val out = ctx.workDir.resolve("${step.name}Output.jar")
            out.writeBytes(body(ctx).toByteArray())
            return out
        }
    }

    @Test
    fun `step vars interpolate bindings and descriptor vars`() {
        val input = dir.resolve("input.jar").toFile()
            .also { it.writeBytes(byteArrayOf(1)) }
        val seen = mutableMapOf<String, String>()
        val fn = object : PipelineFunction {
            override val name = "capture"
            override fun run(step: SubStep, ctx: SubpipelineContext): Path {
                seen.putAll(ctx.vars)
                val out = ctx.workDir.resolve("${step.name}Output.jar")
                out.writeBytes(byteArrayOf(0))
                return out
            }
        }
        val pipeline = DeclaredSubpipeline(
            steps = listOf(SubStep("one", "capture",
                args = mapOf("v" to "a {myVar} b", "in" to "{input}"))),
            vars = mapOf("myVar" to "VAL"))
        val out = executor(pipeline, mapOf("capture" to fn),
            bindings = mapOf("input" to { input }))
            .execute(descriptor, null, null, dir.resolve("work"))
        assertEquals("a VAL b", seen["v"])
        assertEquals(input.absolutePath, seen["in"])
        assertTrue(out != null && out.fileName.toString() == "oneOutput.jar")
    }

    @Test
    fun `prior outputs interpolate through the stepOutput convention`() {
        var secondArg: String? = null
        val first = writeFn("first") { "one-bytes" }
        val second = object : PipelineFunction {
            override val name = "second"
            override fun run(step: SubStep, ctx: SubpipelineContext): Path {
                secondArg = ctx.vars["fromFirst"]
                // the prior output is also visible through ctx.outputs
                assertTrue(ctx.outputs.containsKey("one"))
                val out = ctx.workDir.resolve("${step.name}Output.jar")
                out.writeBytes(byteArrayOf(0))
                return out
            }
        }
        val pipeline = DeclaredSubpipeline(steps = listOf(
            SubStep("one", "first"),
            SubStep("two", "second",
                args = mapOf("fromFirst" to "{oneOutput}"))))
        executor(pipeline,
            mapOf("first" to first, "second" to second))
            .execute(descriptor, null, null, dir.resolve("work"))
        assertTrue(secondArg!!.endsWith("one/oneOutput.jar"), secondArg)
    }

    @Test
    fun `tool table fallback execs through the injected port`() {
        val recorded = mutableListOf<List<String>>()
        var resolvedTool: DeclaredTool? = null
        var resolvedCp: List<Path>? = null
        val input = dir.resolve("in.jar").toFile()
            .also { it.writeBytes(byteArrayOf(1)) }
        val fakeJar = dir.resolve("tools/tool.jar")
            .also { it.parent.createDirectories(); it.writeBytes(byteArrayOf(0)) }
        // mainClass resolved by manifest scan — tool jar carries one
        val manifestJar = dir.resolve("tools/maintool.jar")
            .also { p ->
                p.parent.createDirectories()
                JarOutputStream(p.outputStream(), Manifest().apply {
                    mainAttributes.putValue("Manifest-Version", "1.0")
                    mainAttributes.putValue(
                        Attributes.Name.MAIN_CLASS.toString(), "fake.Tool")
                }).use { }
            }
        val pipeline = DeclaredSubpipeline(
            steps = listOf(SubStep("copy", "copytool")),
            functions = mapOf("copytool" to DeclaredTool(
                coords = listOf("g:maintool:1"),
                args = listOf("--in", "{input}", "--out", "{output}",
                    "--v", "{toolVar}"))),
            vars = mapOf("toolVar" to "tv"))
        var resolvedToolCpJar: Path? = null
        val out = executor(pipeline,
            bindings = mapOf("input" to { input }),
            toolResolver = { coord ->
                assertEquals("g:maintool:1", coord)
                listOf(manifestJar, fakeJar)
            },
            toolExec = { tool, args, _, cp ->
                resolvedTool = tool; resolvedCp = cp
                recorded += args
                // the tool creates its declared {output} itself
                Path.of(args[args.indexOf("--out") + 1])
                    .writeBytes("tool-out".toByteArray())
                resolvedToolCpJar = cp.first()
            }).execute(descriptor, null, null, dir.resolve("work"))
        // main class came from the manifest scan — maintool sorts
        // first because its name matches the coord artifact
        assertEquals("fake.Tool", resolvedTool!!.mainClass)
        assertEquals(listOf(manifestJar, fakeJar), resolvedCp)
        assertEquals(manifestJar, resolvedToolCpJar)
        assertEquals(input.absolutePath, recorded[0][1])
        assertEquals("tv", recorded[0][recorded[0].indexOf("--v") + 1])
        // the tool's output moved to the canonical step path
        assertTrue(out != null)
        assertTrue(out!!.fileName.toString() == "copyOutput.jar")
        assertEquals("tool-out", String(Files.readAllBytes(out)))
    }

    @Test
    fun `tool fallback execs a real java main when no port injected`() {
        // compile a fake tool that writes its --out target
        val src = dir.resolve("src/fake/Tool.java").also {
            it.parent.createDirectories()
            it.writeText("""
                package fake;
                import java.nio.file.*;
                public class Tool {
                    public static void main(String[] a) throws Exception {
                        Files.write(Paths.get(a[1]), "real-exec".getBytes());
                    }
                }
            """.trimIndent())
        }
        val classes = dir.resolve("tool-classes")
        check(ToolProvider.getSystemJavaCompiler().run(
            null, null, null,
            "-d", classes.toString(), src.toString()) == 0)
        val pipeline = DeclaredSubpipeline(
            steps = listOf(SubStep("run", "realtool")),
            functions = mapOf("realtool" to DeclaredTool(
                coords = listOf("g:tool:1"), mainClass = "fake.Tool",
                args = listOf("--out", "{output}"))))
        val out = executor(pipeline,
            toolResolver = { listOf(classes) })
            .execute(descriptor, null, null, dir.resolve("work"))
        assertTrue(out != null && out.exists())
        assertEquals("real-exec", String(Files.readAllBytes(out!!)))
    }

    @Test
    fun `untilStep stops after the named step`() {
        val ran = mutableListOf<String>()
        fun mark(n: String) = object : PipelineFunction {
            override val name = n
            override fun run(step: SubStep, ctx: SubpipelineContext): Path {
                ran += step.name
                val out = ctx.workDir.resolve("${step.name}Output.jar")
                out.writeBytes(byteArrayOf(0)); return out
            }
        }
        val pipeline = DeclaredSubpipeline(steps = listOf(
            SubStep("a", "fa"), SubStep("b", "fb")))
        val out = executor(pipeline,
            mapOf("fa" to mark("fa"), "fb" to mark("fb")))
            .execute(descriptor, null, null, dir.resolve("work"),
                untilStep = "a")
        assertEquals(listOf("a"), ran)
        assertTrue(out!!.endsWith("aOutput.jar"))
    }

    @Test
    fun `an existing canonical output skips the step`() {
        val workDir = dir.resolve("work")
        val seeded = workDir.resolve("one/oneOutput.jar")
            .also { it.parent.createDirectories()
                it.writeBytes("cached".toByteArray()) }
        var ran = false
        val fn = object : PipelineFunction {
            override val name = "shouldnot"
            override fun run(step: SubStep, ctx: SubpipelineContext): Path? {
                ran = true; return null
            }
        }
        val pipeline = DeclaredSubpipeline(
            steps = listOf(SubStep("one", "shouldnot")))
        val out = executor(pipeline, mapOf("shouldnot" to fn))
            .execute(descriptor, null, null, workDir)
        assertTrue(!ran, "existing output must gate the function")
        assertEquals(seeded, out)
        assertArrayEquals("cached".toByteArray(), Files.readAllBytes(out!!))
    }

    @Test
    fun `extractData resolves unbound template vars for tool args`() {
        val extracted = mutableListOf<String>()
        val fmt = object : PipelineDescriptorFormat {
            override val name = "test"
            override fun parse(descriptor: java.io.File, ctx: FormatContext) =
                error("not called")
            override fun extractData(
                descriptor: java.io.File, key: String,
                vars: Map<String, String>, workDir: Path
            ): Path? {
                extracted += key
                return workDir.resolve("data/$key")
                    .also { it.parent.createDirectories()
                        it.writeBytes(byteArrayOf(0)) }
            }
        }
        var seenArg: String? = null
        val pipeline = DeclaredSubpipeline(
            steps = listOf(SubStep("t", "tool")),
            functions = mapOf("tool" to DeclaredTool(
                coords = listOf("g:t:1"), mainClass = "m",
                args = listOf("{extract}"))))
        SubpipelineExecutor(pipeline, fmt, emptyMap(), emptyMap(), { emptyList() },
            toolExec = { _, args, _, _ ->
                seenArg = args.single()
            }).execute(descriptor, null, null, dir.resolve("work"))
        assertEquals(listOf("extract"), extracted)
        assertTrue(seenArg!!.endsWith("data/extract"), seenArg)
    }

    @Test
    fun `null-returning final step yields null so the caller keeps input`() {
        val emit = writeFn("emit") { "first" }
        val noop = object : PipelineFunction {
            override val name = "noop"
            override fun run(step: SubStep, ctx: SubpipelineContext): Path? = null
        }
        val pipeline = DeclaredSubpipeline(steps = listOf(
            SubStep("a", "emit"), SubStep("b", "noop")))
        val out = executor(pipeline,
            mapOf("emit" to emit, "noop" to noop))
            .execute(descriptor, null, null, dir.resolve("work"))
        // `last` tracks the just-ran step — a no-output tail step
        // leaves `last` null; SubpipelineTransformStep then serves
        // the step's own input as the artifact
        assertNull(out)
    }

    @Test
    fun `unknown step function is a wiring error`() {
        val pipeline = DeclaredSubpipeline(
            steps = listOf(SubStep("x", "missing")))
        val e = org.junit.jupiter.api.assertThrows<IllegalStateException> {
            executor(pipeline).execute(
                descriptor, null, null, dir.resolve("work"))
        }
        assertTrue(e.message!!.contains("'missing'"), e.message)
    }

}
