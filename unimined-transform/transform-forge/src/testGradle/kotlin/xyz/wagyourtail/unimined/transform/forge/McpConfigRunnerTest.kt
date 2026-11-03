package xyz.wagyourtail.unimined.transform.forge

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import xyz.wagyourtail.unimined.transform.forge.internal.mcpconfig.McpConfigRunner
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import kotlin.io.path.exists
import kotlin.io.path.outputStream
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * mcpconfig step-graph runner — side selection, step chaining,
 * `{xOutput}` vars, strip/inject steps, function exec.
 */
class McpConfigRunnerTest {

    @TempDir
    lateinit var dir: Path

    private fun jar(name: String, entries: Map<String, String>): Path {
        val jar = dir.resolve(name)
        JarOutputStream(jar.outputStream()).use { out ->
            for ((path, text) in entries) {
                out.putNextEntry(JarEntry(path))
                out.write(text.toByteArray())
                out.closeEntry()
            }
        }
        return jar
    }

    private fun configZip(entries: Map<String, String>): Path =
        jar("mcp_config.zip", entries)

    private data class ExecCall(
        val main: String?, val cp: List<Path>,
        val args: List<String>, val jvmArgs: List<String>, val java: Int?
    )

    private fun runner(
        zip: Path,
        calls: MutableList<ExecCall> = mutableListOf(),
        clientJar: Path = jar("client.jar", mapOf("a.class" to "x")),
        libraries: List<Path> = listOf(dir.resolve("lib1.jar")),
        toolClasspath: (String) -> List<Path> = { listOf(dir.resolve("tool.jar")) }
    ): McpConfigRunner = McpConfigRunner(
        zip, dir.resolve("cache"), "client",
        McpConfigRunner.Env(
            clientJar = { clientJar },
            serverJar = { jar("server.jar", emptyMap()) },
            clientMappings = { dir.resolve("client-mappings.txt") },
            serverMappings = { dir.resolve("server-mappings.txt") },
            libraries = { libraries },
            toolClasspath = toolClasspath,
            exec = object : McpConfigRunner.ExecPort {
                override fun exec(
                    mainClass: String?, classpath: List<Path>,
                    args: List<String>, jvmArgs: List<String>, javaVersion: Int?
                ) {
                    calls.add(ExecCall(mainClass, classpath, args, jvmArgs, javaVersion))
                }
            }
        )
    )

    @Test
    fun `step chain resolves prior outputs`() {
        val zip = configZip(
            mapOf(
                "config.json" to """{
              "data": {},
              "functions": {},
              "steps": {"client": [
                {"type": "downloadClient"},
                {"type": "listLibraries"}
              ]}
            }"""
            )
        )
        val r = runner(zip)
        val result = r.resultFor("listLibraries")
        assertTrue(result.output!!.exists())
        assertEquals(
            "-e=${dir.resolve("lib1.jar").toAbsolutePath()}\n",
            result.output!!.readText()
        )
    }

    @Test
    fun `function step runs exec with templated args`() {
        val tool = jar(
            "tool.jar", mapOf(
                "META-INF/MANIFEST.MF" to
                        "Manifest-Version: 1.0\nMain-Class: com.example.Tool\n\n"
            )
        )
        val zip = configZip(
            mapOf(
                "config.json" to """{
              "data": {},
              "functions": {
                "rename": {
                  "version": "com.tool:rename:1.0:fatjar",
                  "args": ["--input", "{input}", "--out", "{output}"],
                  "jvmargs": ["-Xmx1G"]
                }
              },
              "steps": {"client": [
                {"type": "downloadClient"},
                {"type": "rename", "input": "{downloadClientOutput}"}
              ]}
            }"""
            )
        )
        val calls = mutableListOf<ExecCall>()
        val r = runner(zip, calls, toolClasspath = { listOf(tool) })
        val clientJar = dir.resolve("client.jar")
        val result = r.resultFor("rename")
        assertEquals(1, calls.size)
        val call = calls[0]
        assertEquals("com.example.Tool", call.main)
        assertEquals(listOf(tool), call.cp)
        // {downloadClientOutput} resolved to the client jar path;
        // {output} → the step's own output file
        assertEquals(
            listOf(
                "--input", clientJar.toAbsolutePath().toString(),
                "--out", result.output!!.toAbsolutePath().toString()
            ),
            call.args
        )
        assertEquals(listOf("-Xmx1G"), call.jvmArgs)
    }

    @Test
    fun `strip keeps only mapped classes`() {
        // srg-format mapping file — obf-side names kept in the jar
        val mappings = dir.resolve("mappings.tsrg").apply {
            writeText("a b\n\tf g\n")
        }
        val client = jar(
            "client.jar", mapOf(
                "a.class" to "keep",
                "c.class" to "drop"
            )
        )
        val zip = configZip(
            mapOf(
                "config.json" to """{
              "data": {"mappings": "mappings.tsrg"},
              "functions": {},
              "steps": {"client": [
                {"type": "downloadClient"},
                {"type": "strip", "input": "{downloadClientOutput}"}
              ]}
            }""",
                "mappings.tsrg" to "a b\n\tf g\n"
            )
        )
        // point strip's {mappings} var at the data-extracted path
        val r = McpConfigRunner(
            zip, dir.resolve("cache"), "client",
            McpConfigRunner.Env(
                clientJar = { client },
                serverJar = { jar("s.jar", emptyMap()) },
                clientMappings = { mappings },
                serverMappings = { mappings },
                libraries = { emptyList() },
                toolClasspath = { emptyList() },
                exec = object : McpConfigRunner.ExecPort {
                    override fun exec(
                        mainClass: String?, classpath: List<Path>,
                        args: List<String>, jvmArgs: List<String>, javaVersion: Int?
                    ) {
                    }
                }
            ))
        val result = r.resultFor("strip")
        JarFile(result.output!!.toFile()).use { j ->
            assertEquals(
                setOf("a.class"),
                j.entries().asSequence().map { it.name }.toSet()
            )
        }
    }

    @Test
    fun `inject merges data-prefixed directory contents`() {
        // `inject` step merges the entries under data["inject"] —
        // extracted from the config zip
        val client = jar("client.jar", mapOf("net/minecraft/A.class" to "base"))
        val zip = configZip(
            mapOf(
                "config.json" to """{
              "data": {"inject": "injectable/"},
              "functions": {},
              "steps": {"client": [
                {"type": "downloadClient"},
                {"type": "inject", "input": "{downloadClientOutput}"}
              ]}
            }""",
                "injectable/net/minecraftforge/X.class" to "inj"
            )
        )
        val r = runner(zip, clientJar = client)
        val result = r.resultFor("inject")
        JarFile(result.output!!.toFile()).use { j ->
            assertEquals(
                setOf("net/minecraft/A.class", "net/minecraftforge/X.class"),
                j.entries().asSequence().map { it.name }.toSet()
            )
        }
    }

    @Test
    fun `side selects the side's step list`() {
        val zip = configZip(
            mapOf(
                "config.json" to """{
              "data": {},
              "functions": {},
              "steps": {
                "client": [{"type": "downloadClient"}],
                "server": [{"type": "downloadServer"}]
              }
            }"""
            )
        )
        val serverJar = jar("server.jar", mapOf("s.class" to "x"))
        val r = McpConfigRunner(
            zip, dir.resolve("cache"), "server",
            McpConfigRunner.Env(
                clientJar = { error("unused") },
                serverJar = { serverJar },
                clientMappings = { error("unused") },
                serverMappings = { error("unused") },
                libraries = { emptyList() },
                toolClasspath = { emptyList() },
                exec = object : McpConfigRunner.ExecPort {
                    override fun exec(
                        mainClass: String?, classpath: List<Path>,
                        args: List<String>, jvmArgs: List<String>, javaVersion: Int?
                    ) {
                    }
                }
            ))
        assertEquals(serverJar, r.resultFor("downloadServer").output)
    }
}
