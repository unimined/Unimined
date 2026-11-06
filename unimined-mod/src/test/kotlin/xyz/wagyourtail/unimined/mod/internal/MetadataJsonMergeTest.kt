package xyz.wagyourtail.unimined.mod.internal

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MetadataJsonMergeTest {

    private fun merge(existing: String, generated: String) =
        MetadataJsonMerge.merge(existing.toByteArray(), generated.toByteArray())
            .decodeToString()

    @Test
    fun `objects merge recursively and arrays union`() {
        val out = merge(
            """{"id":"manual","custom":{"a":1,"list":["x"]},"mixins":["m1"]}""",
            """{"id":"gen","custom":{"b":2,"list":["y","x"]},"mixins":["m2","m1"]}"""
        )
        val json = Json.parseToJsonElement(out).jsonObject
        // scalar: generated wins
        assertEquals("gen", json["id"]!!.jsonPrimitive.content)
        // nested object: keys union
        val custom = json["custom"]!!.jsonObject
        assertEquals("1", custom["a"]!!.jsonPrimitive.content)
        assertEquals("2", custom["b"]!!.jsonPrimitive.content)
        // arrays: union, deduped, existing first
        assertEquals(
            listOf("x", "y"),
            custom["list"]!!.jsonArray.map { it.jsonPrimitive.content }
        )
        assertEquals(
            listOf("m1", "m2"),
            json["mixins"]!!.jsonArray.map { it.jsonPrimitive.content }
        )
    }

    @Test
    fun `manual-only keys are preserved`() {
        val out = merge(
            """{"id":"m","entrypoints":{"main":["com.example.Mod"]}}""",
            """{"id":"g","version":"1.0"}"""
        )
        val json = Json.parseToJsonElement(out).jsonObject
        assertEquals(
            "com.example.Mod",
            json["entrypoints"]!!.jsonObject["main"]!!.jsonArray[0]
                .jsonPrimitive.content
        )
        assertEquals("1.0", json["version"]!!.jsonPrimitive.content)
    }

    @Test
    fun `unparseable existing file yields generated`() {
        val out = merge("not json{{{", """{"id":"g"}""")
        assertEquals("""{"id":"g"}""", out)
    }

    @Test
    fun `non-object roots yield generated`() {
        val out = merge("""[1,2]""", """{"id":"g"}""")
        assertEquals("""{"id":"g"}""", out)
    }

    @Test
    fun `generated-only keys are added`() {
        val out = merge("""{"id":"m"}""", """{"id":"g","depends":{"fabric-api":"*"}}""")
        val json = Json.parseToJsonElement(out).jsonObject
        assertEquals(
            "*",
            json["depends"]!!.jsonObject["fabric-api"]!!.jsonPrimitive.content
        )
    }

}
