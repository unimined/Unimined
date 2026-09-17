package xyz.wagyourtail.unimined.util

import java.util.*

@Suppress("UNUSED")
class JSONBuilder {
    private val options: MutableMap<String, String?> = HashMap()
    private val children: MutableList<Any?> = LinkedList()
    private val type: String?
    var inline: Boolean
    var startNewLine: Boolean

    constructor(type: String) {
        this.type = type
        inline = false
        startNewLine = true
    }

    constructor(type: String, inline: Boolean) {
        this.type = type
        this.inline = inline
        startNewLine = !inline
    }

    constructor(type: String, inline: Boolean, startNewLine: Boolean) {
        this.type = type
        this.inline = inline
        this.startNewLine = startNewLine
    }

    fun addOption(key: String, value: String?): JSONBuilder {
        options[key] = value
        return this
    }

    fun addKeyOption(key: String): JSONBuilder {
        options[key] = null
        return this
    }

    fun setId(id: String): JSONBuilder {
        return addStringOption("id", id)
    }

    fun addStringOption(key: String, value: String): JSONBuilder {
        options[key] = escapeJson(value)
        return this
    }

    fun append(vararg children: Any?): JSONBuilder {
        this.children.addAll(listOf(*children))
        return this
    }

    fun pop(index: Int): Any? {
        return children.removeAt(index)
    }

    fun pop(): Any? {
        return children.removeAt(children.size - 1)
    }

    override fun toString(): String {
        val builder = StringBuilder()

        if (type.isNullOrEmpty()) {
            // Root object or array
            if (children.isEmpty()) {
                builder.append(if (options.isEmpty()) "{}" else objectToString())
                return builder.toString()
            }
        }

        builder.append("{").append(type).append(" ")


        // Add options (key-value pairs)
        options.forEach { (key: String?, value: String?) ->
            builder.append("\"").append(key).append("\"")
            if (value != null) {
                builder.append(":").append(value)
            } else {
                builder.append(":null")
            }
            builder.append(" ")
        }

        if (children.isEmpty()) {
            builder.append("}")
        } else {
            var currentInline = this.inline
            for (rawChild in children) {
                if (rawChild is JSONBuilder) {
                    if ((currentInline || rawChild.inline) && !rawChild.startNewLine) {
                        builder.append("")
                    } else {
                        builder.append("\n    ")
                    }
                    builder.append(tabIn(rawChild.toString(), rawChild.inline))
                    currentInline = rawChild.inline
                } else if (rawChild != null) {
                    if (currentInline) {
                        builder.append("")
                    } else {
                        builder.append("\n    ")
                    }
                    builder.append(tabIn(rawChild.toString(), currentInline))
                    currentInline = this.inline
                }
            }
            if (!this.inline) {
                builder.append("\n")
            }
            builder.append("}")
        }

        return builder.toString()
    }

    private fun objectToString(): String {
        val builder = StringBuilder("{")
        var first = true
        options.forEach { (key: String?, value: String?) ->
            if (!first) builder.append(",")
            builder.append("\"").append(key).append("\"")
            if (value != null) {
                builder.append(":").append(value)
            } else {
                builder.append(":null")
            }
            first = false
        }
        builder.append("}")
        return builder.toString()
    }

    private fun tabIn(string: String, inline: Boolean): String {
        if (inline) {
            return string
        }
        return string.replace("\n".toRegex(), "\n    ")
    }

    private fun escapeJson(value: String?): String {
        if (value == null) {
            return "null"
        }
        val escaped = java.lang.StringBuilder()
        for (c in value.toCharArray()) {
            when (c) {
                '"' -> escaped.append("\\\"")
                '\\' -> escaped.append("\\\\")
                '\b' -> escaped.append("\\b")
                '\u000c' -> escaped.append("\\u000c")
                '\n' -> escaped.append("\\n")
                '\r' -> escaped.append("\\r")
                '\t' -> escaped.append("\\t")
                else -> if (c < ' ') {
                    escaped.append("\\u").append(String.format("%04x", c.code))
                } else {
                    escaped.append(c)
                }
            }
        }
        return "\"" + escaped.toString() + "\""
    }
}