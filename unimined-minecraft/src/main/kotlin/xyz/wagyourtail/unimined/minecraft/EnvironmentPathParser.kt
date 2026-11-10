package xyz.wagyourtail.unimined.minecraft

import xyz.wagyourtail.unimined.api.environment.EnvironmentReference

/**
 * Parse a `[:projectPath:]envName` environment reference. Bare names
 * resolve against [projectPath]; `:env` (empty project segment) means
 * the root project — matching Gradle's own `:` path convention.
 */
internal fun parseEnvironmentPath(projectPath: String, path: String): EnvironmentReference {
    if (!path.contains(":")) {
        return EnvironmentReference(projectPath, path)
    }
    val resolvedProject = path.substringBeforeLast(":").ifEmpty { ":" }
    val environmentId = path.substringAfterLast(":")
    require(environmentId.isNotEmpty()) {
        "Environment path '$path' must end with a source-set name"
    }
    return EnvironmentReference(resolvedProject, environmentId)
}
