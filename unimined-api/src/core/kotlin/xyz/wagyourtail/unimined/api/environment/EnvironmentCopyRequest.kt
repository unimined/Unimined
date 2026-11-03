package xyz.wagyourtail.unimined.api.environment

/**
 * A lazily-applied request to reuse selected sections from [source] in
 * [target]. Component plugins consume these requests through
 * [EnvironmentCopyHandler].
 */
data class EnvironmentCopyRequest(
    val source: EnvironmentReference,
    val target: EnvironmentReference,
    val sections: Set<EnvironmentSection>
)
