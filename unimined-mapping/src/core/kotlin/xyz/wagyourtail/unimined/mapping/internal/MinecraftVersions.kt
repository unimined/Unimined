package xyz.wagyourtail.unimined.mapping.internal


/**
 * Ordering for Minecraft version ids sufficient for builtin-channel
 * decisions (`searge` layering, artifact selection).
 *
 * Release ids compare dotted-numerically (`1.16.5` < `1.17`).
 * `"-preN"`/`"-rcN"` suffixes order just before their release; build
 * metadata suffixes (`-20230612.114412`) are ignored. Snapshot ids
 * (`24w14a`) order by year/week and always after every release before
 * the snapshot era began; ids matching neither shape fail loudly.
 */
object MinecraftVersions {

    private val SNAPSHOT = Regex("""^(\d+)w(\d+)[a-z]?$""")
    private val RELEASE = Regex("""^(\d+)\.(\d+)(?:\.(\d+))?(-.*)?$""")

    /** Snapshot week of the first snapshot for the release *after* [release] —
     *  a snapshot compares newer than [release] iff its week is at least this. */
    private val FIRST_SNAPSHOT_WEEK = mapOf(
        "1.12" to 17 * 100 + 43,
        "1.12.2" to 17 * 100 + 43,
        "1.13" to 17 * 100 + 43,
        "1.14" to 18 * 100 + 43,
        "1.15" to 19 * 100 + 34,
        "1.16" to 20 * 100 + 6,
        "1.16.5" to 20 * 100 + 45,
        "1.17" to 20 * 100 + 45,
        "1.18" to 21 * 100 + 37,
        "1.19" to 22 * 100 + 11,
        "1.20" to 23 * 100 + 12,
        "1.21" to 24 * 100 + 18,
    )

    private fun parse(version: String): Triple<Int, Int, Int>? {
        val match = RELEASE.matchEntire(version) ?: return null
        // `-pre`/`-rc`/`Pre-Release` order before the bare release
        val suffix = match.groupValues[4]
        val preReleasePenalty =
            if (suffix.matches(Regex("-(?:pre|rc|Pre-Release).*", RegexOption.IGNORE_CASE))) -1 else 0
        return Triple(
            match.groupValues[1].toInt(),
            match.groupValues[2].toInt(),
            (match.groupValues[3].ifEmpty { "0" }).toInt() * 10 + preReleasePenalty
        )
    }

    /** Compare [a] to [b]; negative when [a] is older. */
    fun compare(a: String, b: String): Int {
        if (a == b) return 0
        val aSnap = SNAPSHOT.matchEntire(a)
        val bSnap = SNAPSHOT.matchEntire(b)
        val aRel = if (aSnap == null) parse(a) else null
        val bRel = if (bSnap == null) parse(b) else null
        if (aRel == null && aSnap == null) {
            throw IllegalStateException("cannot compare Minecraft version '$a' against '$b'")
        }
        if (bRel == null && bSnap == null) {
            throw IllegalStateException("cannot compare Minecraft version '$b' against '$a'")
        }
        if (aSnap != null && bSnap != null) {
            return (aSnap.groupValues[1].toInt() * 100 + aSnap.groupValues[2].toInt())
                .compareTo(bSnap.groupValues[1].toInt() * 100 + bSnap.groupValues[2].toInt())
        }
        if (aSnap != null) {
            // snapshot vs release: newer iff past the release's first-snapshot week
            val week = aSnap.groupValues[1].toInt() * 100 + aSnap.groupValues[2].toInt()
            val boundary = FIRST_SNAPSHOT_WEEK[b] ?: FIRST_SNAPSHOT_WEEK[b.substringBeforeLast('.')]
            return if (boundary != null) (if (week < boundary) -1 else 1)
            else if (week >= 20 * 100) 1 else throw IllegalStateException(
                "cannot compare snapshot '$a' against '$b'"
            )
        }
        if (bSnap != null) return -compare(b, a)
        return compareValuesBy(aRel!!, bRel!!, { it.first }, { it.second }, { it.third })
    }

}
