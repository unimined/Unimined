package xyz.wagyourtail.unimined.minecraft.spi

/**
 * Ordered log of pipeline stage markers an artifact carries —
 * provider-reported prefix ++ step-appended states, in order.
 *
 * `ns:X → patched → ns:Y` and `ns:X → ns:Y → patched` are different
 * states, so queries are positional, never presence-based — that is
 * the position-blind bug this type exists to prevent. Markers are
 * namespaced by step type (`forge:binpatch`, `paperclip:libraryExtract`)
 * and owned by the step-type registry.
 */
class ArtifactStateLog private constructor(
    private val states: List<String>
) {

    /** Every marker, oldest first. Read-only view — positional
     *  queries ([latestSatisfying], [after], [satisfiesAfter]) are
     *  the intended surface; iterating this directly reintroduces
     *  presence-checks. */
    val entries: List<String> get() = states

    /** Append [marker], producing a new log. */
    operator fun plus(marker: String) =
        ArtifactStateLog(states + marker)

    /** Position (index) of the last marker satisfying [predicate],
     *  or -1. */
    fun latestSatisfying(predicate: (String) -> Boolean): Int =
        states.indexOfLast(predicate)

    /** Whether [marker] appears at or after position [index]. */
    fun after(marker: String, index: Int): Boolean =
        index >= 0 && states.subList(index, states.size).contains(marker)

    /** Whether a marker satisfying [predicate] exists at or after
     *  the position of [marker]'s last occurrence. */
    fun satisfiesAfter(predicate: (String) -> Boolean, marker: String): Boolean {
        val at = states.lastIndexOf(marker)
        return at >= 0 && states.subList(at, states.size).any(predicate)
    }

    /** The last marker satisfying [predicate], or null. */
    fun latest(predicate: (String) -> Boolean): String? =
        states.lastOrNull(predicate)

    override fun toString() = states.joinToString(" → ")

    companion object {

        /** Empty log — artifact at raw provider output. */
        val EMPTY = ArtifactStateLog(emptyList())

        /** Log seeded from a provider-reported prefix. */
        fun of(vararg markers: String) = ArtifactStateLog(markers.toList())

    }

}
