package xyz.wagyourtail.unimined.source.internal

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Decompiled-source GL constant fixer — RetroFuturaGradle
 * `GLConstantFixer` parity. Fernflower-era decompilers emit lwjgl
 * calls with raw numeric arguments (`GL11.glBegin(7)`); this rewrites
 * the literals to their named `GLxx` constants
 * (`GL11.glBegin(GL11.GL_QUADS)`) using the same `gl_constants.json`
 * dataset RFG vendors (port under `src/core/resources/`).
 *
 * Only files importing `org.lwjgl.opengl.` are touched; matched
 * package imports are inserted after the `GL11` import line when
 * absent (lwjgl2's constant-owning classes are always imported
 * alongside in decompiled code).
 */
object GlConstantFixer {

    private val PACKAGES = listOf(
        "GL11", "GL12", "GL13", "GL14", "GL15", "GL20", "GL21",
        "ARBMultitexture", "ARBOcclusionQuery", "ARBVertexBufferObject",
        "ARBShaderObjects"
    )

    private val CALL_REGEX =
        Regex("(${PACKAGES.joinToString("|")})\\.(\\w+)\\(.+\\)")
    private val CONSTANT_REGEX = Regex("(?<![-.\\w])\\d+(?![.\\w])")
    private const val IMPORT_CHECK = "import org.lwjgl.opengl."
    private const val IMPORT_REPLACE = "import org.lwjgl.opengl.GL11;"

    /** One `gl_constants.json` group: `functions` maps package →
     *  method names it applies to, `constants` maps the
     *  constant-owning class → number → name. */
    private class Group(
        val functions: Map<String, List<String>>,
        val constants: Map<String, Map<String, String>>
    )

    private val groups: List<Group> by lazy {
        val text = GlConstantFixer::class.java
            .getResource("/gl_constants.json")?.readText()
            ?: return@lazy emptyList()
        Json.parseToJsonElement(text).jsonArray.map { el ->
            val o = el.jsonObject
            Group(
                functions = o.getValue("functions").jsonObject
                    .mapValues { (_, v) ->
                        v.jsonArray.map { it.jsonPrimitive.content }
                    },
                constants = o.getValue("constants").jsonObject
                    .mapValues { (_, v) ->
                        v.jsonObject.mapValues { (_, c) ->
                            c.jsonPrimitive.content
                        }
                    }
            )
        }
    }

    /** Rewrite raw GL literals in [text] to named constants.
     *  Returns the input unchanged when it imports no
     *  `org.lwjgl.opengl` classes. */
    fun fixOGL(text: String): String {
        if (!text.contains(IMPORT_CHECK)) return text
        var out = annotateConstants(text)
        for (pack in PACKAGES) {
            if (out.contains("$pack.")) {
                out = updateImports(out, "org.lwjgl.opengl.$pack")
            }
        }
        return out
    }

    private fun annotateConstants(text: String): String {
        val out = StringBuilder(text.length)
        var last = 0
        for (m in CALL_REGEX.findAll(text)) {
            val fullCall = m.value
            val pack = m.groupValues[1]
            val method = m.groupValues[2]
            val inner = StringBuilder(64)
            var innerLast = 0
            for (cm in CONSTANT_REGEX.findAll(fullCall)) {
                var answer: String? = null
                for (group in groups) {
                    if (group.functions[pack]?.contains(method) == true) {
                        for ((cls, table) in group.constants) {
                            table[cm.value]?.let { answer = "$cls.$it" }
                        }
                    }
                }
                if (answer != null) {
                    inner.append(fullCall, innerLast, cm.range.first)
                    inner.append(answer)
                    innerLast = cm.range.last + 1
                }
            }
            inner.append(fullCall, innerLast, fullCall.length)
            out.append(text, last, m.range.first)
            out.append(inner)
            last = m.range.last + 1
        }
        out.append(text, last, text.length)
        return out.toString()
    }

    private fun updateImports(text: String, imp: String): String =
        if (!text.contains("import $imp;")) {
            text.replace(
                IMPORT_REPLACE,
                IMPORT_REPLACE + System.lineSeparator() + "import $imp;"
            )
        } else text

}
