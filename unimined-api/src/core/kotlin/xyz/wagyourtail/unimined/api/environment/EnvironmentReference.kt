package xyz.wagyourtail.unimined.api.environment

/**
 * Stable reference to a source-set environment. [projectPath] is Gradle's
 * absolute project path (`":"` for the root project); [environmentId] is the
 * source-set/environment name.
 */
data class EnvironmentReference(
    val projectPath: String,
    val environmentId: String
)
