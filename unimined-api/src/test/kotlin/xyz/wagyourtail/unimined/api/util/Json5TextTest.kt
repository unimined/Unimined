package xyz.wagyourtail.unimined.api.util

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * qmj5 (JSON5 `quilt.mod.json`) normalization — comments, trailing
 * commas, single quotes, and bare keys become strict JSON while
 * already-strict input passes through untouched.
 */
class Json5TextTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `line and block comments strip`() {
        val out = json.parseToJsonElement(Json5Text.toJson("""
            {
                // a line comment
                "id": "testmod", /* inline block */
                /* a
                   multi-line block */
                "version": "1.0"
            }
        """)).jsonObject
        assertEquals("testmod", out["id"]!!.jsonPrimitive.content)
        assertEquals("1.0", out["version"]!!.jsonPrimitive.content)
    }

    @Test
    fun `trailing commas drop`() {
        val out = json.parseToJsonElement(Json5Text.toJson("""
            {
                "a": [1, 2, 3,],
                "b": {"x": 1,},
            }
        """)).jsonObject
        assertEquals(3, out["a"]!!.let { it as kotlinx.serialization.json.JsonArray }.size)
        assertEquals(1, out["b"]!!.jsonObject["x"]!!.jsonPrimitive.int)
    }

    @Test
    fun `single quoted strings and bare keys become strict`() {
        val out = json.parseToJsonElement(Json5Text.toJson("""
            {
                schema_version: 1,
                quilt_loader: {id: 'mod-id', group: "com.example",},
            }
        """)).jsonObject
        assertEquals("mod-id", out["quilt_loader"]!!.jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals(1, out["schema_version"]!!.jsonPrimitive.int)
    }

    @Test
    fun `comment-looking text inside strings survives`() {
        val out = json.parseToJsonElement(Json5Text.toJson("""
            {
                "url": "https://example.com//path",
                "glob": "a/*b*/c",
                "quote": "it's a \"test\""
            }
        """)).jsonObject
        assertEquals("https://example.com//path", out["url"]!!.jsonPrimitive.content)
        assertEquals("a/*b*/c", out["glob"]!!.jsonPrimitive.content)
        assertEquals("it's a \"test\"", out["quote"]!!.jsonPrimitive.content)
    }

    @Test
    fun `strict json passes through unchanged`() {
        val strict = """{"a": [1, 2], "b": {"c": "d"}, "e": true}"""
        assertEquals(strict, Json5Text.toJson(strict))
    }
}
