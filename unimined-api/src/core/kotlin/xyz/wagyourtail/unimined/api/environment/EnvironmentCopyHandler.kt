package xyz.wagyourtail.unimined.api.environment

/**
 * Component-owned handler for environment section inheritance.
 *
 * Implementations must not resolve the source project's configuration during
 * registration. Store a lazy reference so multi-project builds do not need
 * `evaluationDependsOn`.
 */
interface EnvironmentCopyHandler {

    /** Sections this component can copy. */
    val sections: Set<EnvironmentSection>

    /** Register deferred section inheritance for [request]. */
    fun registerCopy(request: EnvironmentCopyRequest)

}
