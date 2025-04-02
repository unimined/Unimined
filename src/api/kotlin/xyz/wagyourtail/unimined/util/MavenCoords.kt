package xyz.wagyourtail.unimined.util

@JvmInline
value class MavenCoords(val value: String) {

    constructor(group: String, artifact: String, version: String? = null, classifier: String? = null, extension: String? = null): this(buildString {
        append(group)
        append(':')
        append(artifact)
        if (version != null) {
            append(':')
            append(version)
        }
        if (classifier != null) {
            if (version == null) throw IllegalArgumentException("Cannot have classifier without version")
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
            if (parts.size < 2) return value
            return parts[1]
        }

    val version: String?
        get() = parts.getOrNull(2)

    val classifier: String?
        get() = parts.getOrNull(3)

    val extension: String
        get() = value.substringAfterLast('@', "jar")


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