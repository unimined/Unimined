package xyz.wagyourtail.unimined.api.environment

/**
 * A lazily-applied request to reuse selected sections from [source] in
 * [target]. Component plugins consume these requests through
 * [EnvironmentCopyHandler].
 */
data class EnvironmentCopyRequest(
    val source: EnvironmentReference,
    val target: EnvironmentReference,
    val sections: Set<EnvironmentSection>,
    /**
     * Whether [sections] was an explicit user selection rather than the
     * `COPY_DEFAULT`/`COMBINE_DEFAULT` set — handlers may treat
     * impossible default-inherited sections as a silent no-op while
     * still hard-failing explicit requests (e.g. dependency buckets
     * crossing a project boundary, where configurations cannot extend
     * each other).
     */
    val explicitSections: Boolean = false
)
