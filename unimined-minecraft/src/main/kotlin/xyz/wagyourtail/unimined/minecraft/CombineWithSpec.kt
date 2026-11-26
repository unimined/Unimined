package xyz.wagyourtail.unimined.minecraft

import xyz.wagyourtail.unimined.api.environment.EnvironmentSection

/**
 * One-shot configuration block for the `combineWith(...) { }` forms —
 * carries the edge's packaging mode plus an optional explicit section
 * selection.
 *
 * Plain value fields only: the spec is consumed within the
 * `combineWith` call itself and is never retained or serialized, so a
 * later reconfiguration of the same edge simply records again.
 */
class CombineWithSpec {

    /**
     * Packaging mode for the combined output — case-insensitive one of
     * `"shadow"` (the default: embed flat in the packaged artifact),
     * `"none"` (compose classpaths only, keep the artifact to the env's
     * own output), or `"jarinjar"` (declared, not yet implemented).
     * Validated lazily at the point of use — assignment never fails;
     * the error lists the valid values.
     */
    var packaging: String = "shadow"
        set(value) {
            field = value
            packagingExplicit = true
        }

    /** Whether [packaging] was assigned inside the block — unassigned
     *  blocks leave any previously declared mode for the edge alone
     *  instead of resetting it to the shadow default. */
    internal var packagingExplicit = false
        private set

    private val selectedSections = mutableSetOf<EnvironmentSection>()

    /**
     * Restrict the copied environment sections to exactly the given
     * selection, replacing the `COMBINE_DEFAULT` set — the spec-form
     * analog of `combineWith(path, *sections)` (missing section
     * handlers hard-error the same way).
     */
    fun sections(vararg sections: EnvironmentSection) {
        selectedSections += sections
    }

    /** The explicit section selection, or null for the combine
     *  defaults. */
    internal val sectionsOrNull: Set<EnvironmentSection>?
        get() = selectedSections.ifEmpty { null }

    /** The packaging mode to record for the edge, or null to leave a
     *  previously declared mode untouched. */
    internal val packagingOrNull: String?
        get() = if (packagingExplicit) packaging else null
}
