package xyz.wagyourtail.unimined.transform.internal

/**
 * Numeric-dotted version comparison for loader-version feature gates —
 * `(2, 0, 0)` semantics on the leading `x.y.z` portion; suffixes after
 * `-`/`+` are stripped, missing components count as `0`, non-numeric
 * components fail closed (`false`).
 */
fun versionAtLeast(version: String?, bound: String): Boolean {
    if (version == null) return false
    val have = versionParts(version) ?: return false
    val want = versionParts(bound) ?: return false
    for (i in 0 until maxOf(have.size, want.size)) {
        val h = have.getOrElse(i) { 0 }; val w = want.getOrElse(i) { 0 }
        if (h != w) return h > w
    }
    return true
}

/** `x.y.z` components of a version, suffix-stripped — `null` on
 *  non-numeric components (fails closed). */
private fun versionParts(v: String): List<Int>? =
    v.substringBefore('-').substringBefore('+')
        .split('.').map { it.toIntOrNull() ?: return null }
