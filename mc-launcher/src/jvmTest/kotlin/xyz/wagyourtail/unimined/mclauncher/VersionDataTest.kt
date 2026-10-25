package xyz.wagyourtail.unimined.mclauncher

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement

class VersionDataTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun data(text: String): VersionData =
        VersionData.parse(json.parseToJsonElement(text) as JsonObject)

    @Test
    fun `arguments decode bare string and rules-gated object forms`() {
        val doc = data(
            """{
              "arguments": {
                "game": [
                  "--demo",
                  {"rules": [{"action": "allow", "os": {"name": "windows"}}],
                   "value": "--win-flag"},
                  {"rules": [{"action": "allow", "features": {"has_demo": true}}],
                   "value": ["--a", "--b"]}
                ]
              }
            }"""
        )
        val game = doc.gameArguments!!
        assertEquals(listOf("--demo"), game[0].value)
        assertEquals(emptyList<VersionData.Rule>(), game[0].rules)
        assertEquals(listOf("--win-flag"), game[1].value)
        assertEquals("windows", game[1].rules[0].os?.name)
        assertEquals(listOf("--a", "--b"), game[2].value)
        assertEquals(mapOf("has_demo" to true), game[2].rules[0].features)
        assertNull(doc.jvmArguments)
    }

    @Test
    fun `library downloads decode artifact and classifiers`() {
        val doc = data(
            """{
              "libraries": [{
                "name": "org.lwjgl:lwjgl:3.3.1",
                "natives": {"linux": "natives-linux"},
                "extract": {"exclude": ["META-INF/"]},
                "downloads": {
                  "artifact": {"url": "https://x/l.jar", "sha1": "aa", "size": 100},
                  "classifiers": {
                    "natives-linux": {"url": "https://x/n.jar", "sha1": "bb"}
                  }
                }
              }]
            }"""
        )
        val lib = doc.libraries.single()
        assertEquals("natives-linux", lib.nativeClassifier(OperatingSystem.LINUX))
        assertEquals(listOf("META-INF/"), lib.extractExclude)
        val downloads = lib.downloads!!
        assertEquals("https://x/l.jar", downloads.artifact!!.url)
        assertEquals("https://x/n.jar", downloads.classifiers["natives-linux"]!!.url)
    }

    @Test
    fun `encode then decode round-trips argument entries`() {
        val doc = data(
            """{
              "id": "1.20.1",
              "arguments": {
                "game": [
                  "--bare",
                  {"rules": [{"action": "allow", "os": {"name": "osx"}}],
                   "value": ["--x", "--y"]}
                ]
              }
            }"""
        )
        val reparsed = VersionData.parse(
            json.encodeToJsonElement(VersionData.serializer(), doc) as JsonObject
        )
        assertEquals(doc.id, reparsed.id)
        assertEquals(doc.gameArguments!!.map { it.value }, reparsed.gameArguments!!.map { it.value })
        assertEquals("osx", reparsed.gameArguments!![1].rules[0].os?.name)
    }

}
