package xyz.wagyourtail.unimined.remap.internal

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.jar.Attributes
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import java.util.zip.ZipEntry
import kotlin.io.path.outputStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue

class JarLintTest {

    @TempDir
    lateinit var dir: Path

    private fun jar(name: String = "mod.jar", entries: Map<String, String>): Path {
        val path = dir.resolve(name)
        JarOutputStream(path.outputStream(), Manifest().apply {
            mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
        }).use { out ->
            entries.forEach { (entryName, content) ->
                out.putNextEntry(ZipEntry(entryName))
                out.write(content.toByteArray())
                out.closeEntry()
            }
        }
        return path
    }

    private fun manifestJar(name: String, namespace: String): Path {
        val path = dir.resolve(name)
        JarOutputStream(path.outputStream(), Manifest().apply {
            mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
            mainAttributes.putValue(JarLint.NAMESPACE_ATTRIBUTE, namespace)
        }).use { }
        return path
    }

    @Test
    fun `clean fabric jar lints without findings`() {
        val j = jar(entries = mapOf(
            "fabric.mod.json" to """{
                "id": "mymod", "version": "1.0",
                "accessWidener": "mymod.accesswidener",
                "mixins": ["mymod.mixins.json"],
                "jars": [{"file": "META-INF/jars/nested.jar"}],
                "icon": "icon.png",
                "entrypoints": {"main": ["com.example.Init"]}
            }""",
            "mymod.accesswidener" to "accessWidener v1 named",
            "mymod.mixins.json" to """{"package": "com.example.mixin", "mixins": ["FooMixin"]}""",
            "com/example/mixin/FooMixin.class" to "x",
            "META-INF/jars/nested.jar" to "x",
            "icon.png" to "x",
            "com/example/Init.class" to "x",
        ))
        assertTrue(JarLint.lint(j).isEmpty())
    }

    @Test
    fun `dangling access widener path is flagged`() {
        val j = jar(entries = mapOf(
            "fabric.mod.json" to """{"id": "m", "version": "1",
                "accessWidener": "missing.accesswidener"}"""
        ))
        val problems = JarLint.lint(j)
        assertEquals(1, problems.size)
        assertTrue(problems[0].message.contains("missing.accesswidener"))
    }

    @Test
    fun `dangling mixin config and mixin class are flagged`() {
        val j = jar(entries = mapOf(
            "fabric.mod.json" to """{"id": "m", "version": "1",
                "mixins": ["gone.mixins.json", {"config": "also.gone.json"}]}""",
            "present.mixins.json" to """{"package": "p", "mixins": ["NotHere"]}""",
        ))
        val problems = JarLint.lint(j)
        assertEquals(3, problems.size)
        assertTrue(problems.any { it.message.contains("gone.mixins.json") })
        assertTrue(problems.any { it.message.contains("also.gone.json") })
        assertTrue(problems.any { it.message.contains("p.NotHere") })
    }

    @Test
    fun `dangling nested jar and icon are flagged`() {
        val j = jar(entries = mapOf(
            "fabric.mod.json" to """{"id": "m", "version": "1",
                "jars": [{"file": "META-INF/jars/no.jar"}],
                "icon": "icon.png"}"""
        ))
        val problems = JarLint.lint(j)
        assertEquals(2, problems.size)
    }

    @Test
    fun `missing entrypoint class is flagged`() {
        val j = jar(entries = mapOf(
            "fabric.mod.json" to """{"id": "m", "version": "1",
                "entrypoints": {"main": ["com.example.Gone"],
                    "client": [{"value": "com.example.AlsoGone"}]}}"""
        ))
        assertEquals(2, JarLint.lint(j).size)
    }

    @Test
    fun `namespace marker missing and mismatched`() {
        val noMarker = jar("nomarker.jar", emptyMap())
        assertTrue(JarLint.lint(noMarker, "intermediary")
            .any { it.message.contains("missing") })
        val wrong = manifestJar("wrong.jar", "named")
        assertTrue(JarLint.lint(wrong, "intermediary")
            .any { it.message.contains("'named'") })
        val right = manifestJar("right.jar", "intermediary")
        assertTrue(JarLint.lint(right, "intermediary").isEmpty())
    }

    @Test
    fun `plugin yml main class must exist`() {
        val j = jar(entries = mapOf(
            "plugin.yml" to "name: p\nversion: 1\nmain: com.example.NotThere\n"
        ))
        val problems = JarLint.lint(j)
        assertEquals(1, problems.size)
        assertTrue(problems[0].message.contains("com.example.NotThere"))
    }

    @Test
    fun `mods toml requires loader and license`() {
        val j = jar(entries = mapOf(
            "META-INF/mods.toml" to "modLoader=\"javafml\"\nlicense=\"MIT\"\n"
        ))
        assertTrue(JarLint.lint(j).isEmpty())
        val bad = jar("bad.jar", mapOf("META-INF/mods.toml" to "license=\"MIT\"\n"))
        assertEquals(1, JarLint.lint(bad).size)
    }
}
