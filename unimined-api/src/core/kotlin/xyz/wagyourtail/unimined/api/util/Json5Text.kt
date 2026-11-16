package xyz.wagyourtail.unimined.api.util

/**
 * JSON5 → strict-JSON normalizer for the `quilt.mod.json` (qmj5)
 * subset: line and block comments, trailing commas, single-quoted
 * strings, and unquoted identifier keys are rewritten to strict JSON;
 * everything else passes through. Idempotent on strict JSON — safe to
 * apply unconditionally before kotlinx decoding.
 *
 * Not a full JSON5 parser: JSON5-only number forms (hex, leading `.`,
 * `Infinity`, `NaN`) pass through and fail strict decode — acceptable
 * since mod-metadata scalars are strings/booleans. Best-effort by
 * design: a malformed input produces malformed output and the caller's
 * `runCatching` decode handles it.
 */
object Json5Text {

    /** Returns strict-JSON text equivalent to the JSON5 [text]. */
    fun toJson(text: String): String {
        val out = StringBuilder(text.length + 8)
        // output index of the last emitted `,` while only whitespace
        // has been written since — dropped when a `}`/`]` follows
        var pendingComma = -1
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '/' && i + 1 < text.length && text[i + 1] == '/' -> {
                    while (i < text.length && text[i] != '\n') i++
                }
                c == '/' && i + 1 < text.length && text[i + 1] == '*' -> {
                    i += 2
                    while (i + 1 < text.length &&
                        !(text[i] == '*' && text[i + 1] == '/')) i++
                    i = minOf(i + 2, text.length)
                }
                c == '"' || c == '\'' -> {
                    val quote = c
                    pendingComma = -1
                    out.append('"'); i++
                    while (i < text.length && text[i] != quote) {
                        val s = text[i]
                        if (s == '\\' && i + 1 < text.length) {
                            val n = text[i + 1]
                            when {
                                // `\'` unescapes in a single-quoted
                                // string; `\"` stays escaped in the
                                // double-quoted output
                                quote == '\'' && n == '\'' -> out.append('\'')
                                else -> out.append(s).append(n)
                            }
                            i += 2
                            continue
                        }
                        // bare `"` inside a single-quoted string must
                        // re-escape for the double-quoted output
                        if (s == '"') out.append("\\\"") else out.append(s)
                        i++
                    }
                    out.append('"')
                    i++ // closing quote (or EOF — malformed, caller's problem)
                }
                c == ',' -> {
                    pendingComma = out.length
                    out.append(c); i++
                }
                c.isWhitespace() -> { out.append(c); i++ }
                c == '}' || c == ']' -> {
                    if (pendingComma >= 0) out.deleteCharAt(pendingComma)
                    pendingComma = -1
                    out.append(c); i++
                }
                else -> {
                    pendingComma = -1
                    // structural punctuation or a value/key run — an
                    // unquoted run followed by `:` is a bare key
                    if (c == '{' || c == '[' || c == ':') {
                        out.append(c); i++
                    } else {
                        var j = i
                        while (j < text.length && isIdent(text[j])) j++
                        var k = j
                        while (k < text.length && text[k].isWhitespace()) k++
                        if (j > i && k < text.length && text[k] == ':') {
                            out.append('"').append(text, i, j).append('"')
                        } else {
                            out.append(text, i, j)
                        }
                        i = j
                    }
                }
            }
        }
        return out.toString()
    }

    private fun isIdent(c: Char): Boolean =
        c.isLetterOrDigit() || c == '_' || c == '$' || c == '-' || c == '.'
}
