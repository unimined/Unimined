package xyz.wagyourtail.unimined.api.util

import org.gradle.api.GradleException
import org.gradle.api.artifacts.Dependency

/**
 * `group:name[:version]` DSL notation → a [Dependency] predicate —
 * the shared parser behind the `forceRemap` verbs on
 * `MinecraftEnvironment` and `RemappedViewSpec`. A two-segment
 * notation matches any version of the module; a third segment pins
 * it. Anything else — a missing name (`"foo"`), blank segments
 * (`":"`, `"g:"`), or extra fields (`"g:n:v:classifier"`) — fails
 * fast with an actionable error rather than crashing on index
 * access or silently matching nothing.
 *
 * [context] names the rejecting DSL surface in the error (e.g.
 * `"forceRemap on minecraft environment 'client'"`) so a bad
 * coordinate points back at the spec that refused it.
 */
fun dependencyNotationPredicate(
    notation: String,
    context: String
): (Dependency) -> Boolean {
    val parts = notation.split(':')
    if (parts.size !in 2..3 || parts.any(String::isBlank)) {
        throw GradleException(
            "unimined: invalid dependency notation '$notation' " +
                    "in $context — expected 'group:name[:version]' (e.g. " +
                    "\"net.fabricmc:fabric-api\" or " +
                    "\"com.example:mod:1.0\")"
        )
    }
    val group = parts[0]
    val name = parts[1]
    val version = parts.getOrNull(2)
    return { dep ->
        dep.group == group && dep.name == name &&
                (version == null || dep.version == version)
    }
}
