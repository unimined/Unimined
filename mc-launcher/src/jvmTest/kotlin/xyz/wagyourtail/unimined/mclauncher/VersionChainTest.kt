package xyz.wagyourtail.unimined.mclauncher

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.Test
import kotlin.test.assertFailsWith

/** `inheritsFrom` chain merge semantics. */
class VersionChainTest {

    private fun obj(json: String): JsonObject =
        Json.parseToJsonElement(json) as JsonObject

    private fun list(document: JsonObject, section: String): List<String> =
        (document["arguments"] as JsonObject)[section]!!.jsonArray.map {
            it.jsonPrimitive.content
        }

    @Test
    fun `child scalars win and parent fields survive`() {
        val merged = VersionChain.resolve(
            obj("""{"id":"child","inheritsFrom":"parent","mainClass":"child.Main"}"""),
            { obj("""{"id":"parent","mainClass":"parent.Main","assets":"5"}""") }
        )
        assertEquals("child.Main", merged["mainClass"]!!.jsonPrimitive.content)
        assertEquals("5", merged["assets"]!!.jsonPrimitive.content)
        assertFalse(merged.containsKey("inheritsFrom"))
    }

    @Test
    fun `deep chains merge and cycles fail`() {
        val docs = mapOf(
            "a" to obj("""{"id":"a","inheritsFrom":"b","arguments":{"game":["--a"]}}"""),
            "b" to obj("""{"id":"b","inheritsFrom":"c","arguments":{"game":["--b"]}}"""),
            "c" to obj("""{"id":"c","arguments":{"game":["--c"]}}""")
        )
        val merged = VersionChain.resolve(docs.getValue("a"), docs::getValue)
        assertEquals(listOf("--c", "--b", "--a"), list(merged, "game"))

        val cyclic = mapOf(
            "x" to obj("""{"id":"x","inheritsFrom":"y"}"""),
            "y" to obj("""{"id":"y","inheritsFrom":"x"}""")
        )
        assertFailsWith<IllegalArgumentException> {
            VersionChain.resolve(cyclic.getValue("x"), cyclic::getValue)
        }
    }

    @Test
    fun `arguments append with later duplicate keys winning`() {
        val merged = VersionChain.merge(
            obj("""{"arguments":{"game":["--username","parent","--width","100"]}}"""),
            obj("""{"arguments":{"game":["--username","child","--demo"]}}""")
        )
        assertEquals(
            listOf("--width", "100", "--username", "child", "--demo"),
            list(merged, "game")
        )
    }

    @Test
    fun `minecraftArguments child replaces wholesale`() {
        val merged = VersionChain.merge(
            obj("""{"minecraftArguments":"--username parent"}"""),
            obj("""{"minecraftArguments":"--username child"}""")
        )
        assertEquals("--username child", merged["minecraftArguments"]!!.jsonPrimitive.content)
    }

    @Test
    fun `libraries concatenate deduped by coordinate with child winning`() {
        val merged = VersionChain.merge(
            obj("""{"libraries":[{"name":"a:b:1","old":true},{"name":"c:d:1"}]}"""),
            obj("""{"libraries":[{"name":"a:b:1","new":true}]}""")
        )
        val libraries = merged["libraries"]!!.jsonArray
        assertEquals(2, libraries.size)
        val first = libraries[0] as JsonObject
        assertEquals("a:b:1", first["name"]!!.jsonPrimitive.content)
        assertEquals(true, first["new"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `downloads merge per key`() {
        val merged = VersionChain.merge(
            obj("""{"downloads":{"client":{"url":"p"},"server":{"url":"p"}}}"""),
            obj("""{"downloads":{"client":{"url":"c"}}}""")
        )
        val downloads = merged["downloads"] as JsonObject
        assertEquals("c", (downloads["client"] as JsonObject)["url"]!!.jsonPrimitive.content)
        assertEquals("p", (downloads["server"] as JsonObject)["url"]!!.jsonPrimitive.content)
    }

}
