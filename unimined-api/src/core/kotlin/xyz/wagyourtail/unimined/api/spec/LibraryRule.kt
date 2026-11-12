package xyz.wagyourtail.unimined.api.spec

/**
 * Rich version-JSON library rule — the 1.x `replaceLibraryVersion`
 * surface. A library coordinate `group:name[:version[:classifier]]`
 * whose components fully [Regex.matches] [group]/[name]/[classifier]
 * is rewritten with the version [version] computes from the matched
 * component (the existing version for 3+-part coords, the name for a
 * bare `group:name` dep — 1.x parity); a `null` return drops the
 * library entirely.
 */
data class LibraryRule(
    val group: Regex = Regex(".*"),
    val name: Regex = Regex(".*"),
    val classifier: Regex = Regex(".*"),
    val version: (String) -> String?
)

/**
 * Rules providers always evaluate — 1.x registered these on the
 * provider before user DSL ran, so they win over user rules.
 * `java-objc-bridge`'s `natives-osx` classifier requests a dylib
 * Mojang stopped shipping; nothing can satisfy it on modern macOS.
 */
val DEFAULT_LIBRARY_RULES = listOf(
    LibraryRule(
        group = Regex("ca\\.weblite"),
        name = Regex("java-objc-bridge"),
        classifier = Regex("natives-osx")
    ) { null }
)

/**
 * Evaluate the env's library rules over a version-JSON coordinate,
 * 1.x `libraryReplaceMap` order:
 *
 * 1. [coordFilters] (1.x `libraryFilter(String)`) — each returns the
 *    replacement coord or `null` to drop; last-declared runs first.
 * 2. [DEFAULT_LIBRARY_RULES], then [rules] (1.x
 *    `replaceLibraryVersion` — user rules override the loader
 *    conventions), then the simple [substitutions] pairs.
 * 3. No match → the coord passes through unchanged.
 *
 * First deciding match wins: `version` → `null` drops the library,
 * a non-null result rebuilds `group:name:newVersion[:rest…]`.
 */
fun applyLibraryRules(
    coord: String,
    substitutions: List<Pair<String, String>>,
    rules: List<LibraryRule> = emptyList(),
    coordFilters: List<(String) -> String?> = emptyList()
): String? {
    var c = coord
    for (f in coordFilters.asReversed()) {
        c = f(c) ?: return null
    }
    val parts = c.split(':')
    for (rule in DEFAULT_LIBRARY_RULES + rules) {
        val (matched, replacement) = rule.applyTo(parts)
        if (matched) return replacement
    }
    if (parts.size >= 3) {
        val groupName = "${parts[0]}:${parts[1]}"
        val version = substitutions.firstOrNull { (key, _) ->
            if (':' in key) groupName == key else groupName.startsWith(key)
        }?.second
        if (version != null) {
            return (listOf(parts[0], parts[1], version) + parts.drop(3))
                .joinToString(":")
        }
    }
    return c
}

/** `(matched, replacement)` — matched-with-null is a drop decision,
 *  not a miss. */
private fun LibraryRule.applyTo(parts: List<String>): Pair<Boolean, String?> {
    fun decide(input: String, rebuild: (String) -> String): Pair<Boolean, String?> {
        val v = version(input) ?: return true to null
        return true to rebuild(v)
    }
    return when {
        parts.size == 2 && parts[0].matches(group) && parts[1].matches(name) ->
            decide(parts[1]) { "${parts[0]}:${parts[1]}:$it" }
        parts.size == 3 && parts[0].matches(group) && parts[1].matches(name) ->
            decide(parts[2]) { "${parts[0]}:${parts[1]}:$it" }
        parts.size >= 4 && parts[0].matches(group) && parts[1].matches(name) &&
            parts[3].matches(classifier) ->
            decide(parts[2]) {
                (listOf(parts[0], parts[1], it) + parts.drop(3))
                    .joinToString(":")
            }
        else -> false to null
    }
}
