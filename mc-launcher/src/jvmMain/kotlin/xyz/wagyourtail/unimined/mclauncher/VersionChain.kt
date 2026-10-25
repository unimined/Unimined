package xyz.wagyourtail.unimined.mclauncher

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * `inheritsFrom` resolution: merge a version JSON with its parent chain
 * into one effective document — recursive N-deep chains with cycle
 * detection. Scalar fields are child-wins; `arguments.*` lists append
 * (later duplicate option keys win); `libraries` concatenate deduped by
 * coordinate with the child winning; `downloads` merge per key.
 */
object VersionChain {

    /** Scalars where a present child value replaces the parent's. */
    private val CHILD_WINS = setOf(
        "id", "type", "mainClass", "assetIndex", "assets", "javaVersion",
        "minimumLauncherVersion", "logging", "complianceLevel", "time", "releaseTime"
    )

    /**
     * Merge [root] with its `inheritsFrom` chain into one effective
     * document. [parent] fetches a parent document by version id.
     */
    fun resolve(
        root: JsonObject,
        parent: (id: String) -> JsonObject
    ): JsonObject = resolveChain(root, parent, linkedSetOf())

    private fun resolveChain(
        document: JsonObject,
        parent: (id: String) -> JsonObject,
        visiting: LinkedHashSet<String>
    ): JsonObject {
        val parentId = document["inheritsFrom"]?.jsonPrimitive?.content ?: return document
        val selfId = document["id"]?.jsonPrimitive?.content ?: parentId
        require(visiting.add(selfId)) {
            "cyclic version JSON inheritsFrom chain involving '$selfId'"
        }
        try {
            val parentDocument = resolveChain(parent(parentId), parent, visiting)
            return merge(parentDocument, document)
        } finally {
            visiting.remove(selfId)
        }
    }

    /**
     * Merge [child] over [parent] with launcher-compatible semantics.
     * Both documents are expected to already have their own chains
     * resolved.
     */
    fun merge(parent: JsonObject, child: JsonObject): JsonObject {
        val merged = LinkedHashMap<String, JsonElement>(parent)

        for ((key, value) in child) {
            when (key) {
                "inheritsFrom" -> Unit
                "libraries" -> merged["libraries"] = mergeLibraries(
                    parent["libraries"] as? JsonArray,
                    value as? JsonArray
                )
                "downloads" -> merged["downloads"] = JsonObject(
                    LinkedHashMap<String, JsonElement>(
                        (parent["downloads"] as? JsonObject) ?: JsonObject(emptyMap())
                    ).apply { (value as? JsonObject)?.forEach { (k, v) -> put(k, v) } }
                )
                "arguments" -> merged["arguments"] = mergeArguments(
                    parent["arguments"] as? JsonObject, value as? JsonObject
                )
                // minecraftArguments + all scalars: child wins wholesale
                else -> merged[key] = value
            }
        }
        merged.remove("inheritsFrom")
        return JsonObject(merged)
    }

    /**
     * `arguments.game`/`arguments.jvm`: parent entries first, child
     * appended; a later entry wins when it introduces a duplicate option
     * key (a `--flag` token or `--flag=value`).
     */
    private fun mergeArguments(parent: JsonObject?, child: JsonObject?): JsonObject {
        val merged = LinkedHashMap<String, JsonElement>()
        parent?.forEach { (k, v) -> merged[k] = v }
        child?.forEach { (key, value) ->
            if (key == "game" || key == "jvm") {
                merged[key] = JsonArray(
                    dedupeOptions(
                        (parent?.get(key) as? JsonArray ?: JsonArray(emptyList())).toList() +
                            (value as? JsonArray ?: JsonArray(emptyList())).toList()
                    )
                )
            } else {
                merged[key] = value
            }
        }
        return JsonObject(merged)
    }

    /**
     * Duplicate option keys: a bare `--flag` or `--flag value` pair claims
     * `flag`; `--flag=value` claims `flag` inline. The last claimant wins;
     * losing flags drop their paired value arg (when the flag has no `=`
     * and a non-flag element follows).
     */
    private fun dedupeOptions(entries: List<JsonElement>): List<JsonElement> {
        val lastFlagIndex = HashMap<String, Int>()
        entries.forEachIndexed { index, element ->
            val token = element.jsonPrimitive.content
            if (token.startsWith("--")) {
                lastFlagIndex[token.substringBefore('=')] = index
            }
        }
        val keep = BooleanArray(entries.size) { true }
        entries.forEachIndexed { index, element ->
            val token = element.jsonPrimitive.content
            if (!token.startsWith("--")) return@forEachIndexed
            val key = token.substringBefore('=')
            if (lastFlagIndex[key] != index) {
                keep[index] = false
                if (!token.contains('=') && index + 1 < entries.size &&
                    !entries[index + 1].jsonPrimitive.content.startsWith("--")
                ) {
                    keep[index + 1] = false // losing flag's value arg
                }
            }
        }
        return entries.filterIndexed { index, _ -> keep[index] }
    }

    /** Libraries concatenate; the child's entry wins a duplicate `name`. */
    private fun mergeLibraries(parent: JsonArray?, child: JsonArray?): JsonArray {
        val order = ArrayList<String>()
        val byName = LinkedHashMap<String, JsonObject>()
        for (element in (parent ?: JsonArray(emptyList())) + (child ?: JsonArray(emptyList()))) {
            val lib = element.jsonObject
            val name = lib["name"]?.jsonPrimitive?.content ?: continue
            if (!byName.containsKey(name)) order.add(name)
            byName[name] = lib
        }
        return JsonArray(order.map { byName.getValue(it) })
    }

}
