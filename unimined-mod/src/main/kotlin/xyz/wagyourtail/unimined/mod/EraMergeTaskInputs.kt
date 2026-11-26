package xyz.wagyourtail.unimined.mod

import java.io.Serializable

/** Serializable task-input row for one era artifact. */
data class EraJarInput(
    /** Era/environment name. */
    val name: String,
    /** Effective partition (`nest` or `flatten`). */
    val partition: String,
    /** Absolute artifact path; the file itself is declared separately. */
    val artifactPath: String
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/** Serializable task-input row for one physically embedded additional nested jar. */
data class AdditionalNestedJarInput(
    /** Jar-relative target inside the generated nested jar. */
    val targetPath: String,
    /** Candidate source files; the first existing file is embedded. */
    val sourcePaths: List<String>
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}
