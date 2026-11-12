package xyz.wagyourtail.unimined.api.util

import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedArtifactResult

/**
 * `group:artifact:version[:classifier][@extension]` coordinate string —
 * the identifier loaders use when selecting embedded (jar-in-jar)
 * candidates. The loaders' matching semantics live in
 * [ResolvedArtifactResult.getCoords]: it *constructs* these from the
 * variant's component id, resolved capabilities, and file-name
 * classifier detection — the same inputs forge/fabric first-party
 * tooling reads, so embedded ids line up. The getters here parse the
 * string form (Gradle `g:a:v:c@e` notation) for DSL-facing uses like
 * `include("g:a:v")` lookups.
 */
@JvmInline
value class MavenCoords(val value: String) {

    constructor(
        group: String,
        artifact: String,
        version: String? = null,
        classifier: String? = null,
        extension: String? = null
    ) : this(buildString {
        append(group)
        append(':')
        append(artifact)
        if (version != null) {
            append(':')
            append(version)
        }
        if (classifier != null) {
            if (version == null) throw IllegalArgumentException(
                "Cannot have classifier without version"
            )
            append(':')
            append(classifier)
        }
        if (extension != null) {
            append('@')
            append(extension)
        }
    })

    val parts: List<String>
        get() = value.substringBeforeLast('@').split(":", limit = 4)

    val group: String?
        get() {
            if (parts.size < 2) return null
            return parts[0]
        }

    val artifact: String
        get() {
            if (parts.size < 2) return value.substringBeforeLast('@')
            return parts[1]
        }

    val version: String?
        get() = parts.getOrNull(2)

    val classifier: String?
        get() = parts.getOrNull(3)

    val extension: String
        get() = value.substringAfterLast('@', "jar")

    /** `artifact-version[-classifier].ext` — the filename embedded jars
     *  are packed under (`META-INF/jars|jarjar/<fileName>`). */
    val fileName: String
        get() = buildString {
            append(artifact)
            append('-')
            if (version == null) {
                append("unspecified")
            } else {
                append(version)
            }
            if (classifier != null) {
                append('-')
                append(classifier)
            }
            append('.')
            append(extension)
        }

    override fun toString() = value

}

/**
 * Resolved artifact → [MavenCoords], identical to forge/fabric
 * first-party tooling: the module component id wins, but a resolved
 * capability whose coordinates differ (relocated/shadowed variants)
 * overrides it, and a `<artifact>-<version>-<classifier>.<ext>` file
 * name contributes the classifier. Keeping this byte-for-byte is what
 * makes the ids of jar-in-jar dependencies match the loaders'
 * candidate selection.
 */
fun ResolvedArtifactResult.getCoords(): MavenCoords {
    val owner = this.variant.owner

    var location = if (owner is ModuleComponentIdentifier) {
        MavenCoords(owner.group, owner.module, owner.version)
    } else {
        null
    }

    val capabilityLocations = this.variant.capabilities.map {
        MavenCoords(it.group, it.name, it.version)
    }

    if (!capabilityLocations.isEmpty() &&
        (location == null || !capabilityLocations.contains(location))) {
        location = capabilityLocations[0]
    }

    if (location == null) {
        error("unknown dependency type ${this.variant.owner}")
    }

    val classifierPrefix = "${location.artifact}-${location.version}-"

    if (this.file.name.startsWith(classifierPrefix)) {
        location = MavenCoords(
            location.group!!,
            location.artifact,
            location.version,
            this.file.nameWithoutExtension.substring(classifierPrefix.length),
            this.file.extension
        )
    } else {
        location = MavenCoords(
            location.group!!,
            location.artifact,
            location.version,
            null,
            this.file.extension
        )
    }

    return location
}
