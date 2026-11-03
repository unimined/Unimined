package xyz.wagyourtail.unimined.mod

import java.io.Serializable

/**
 * Serializable snapshot of the loader-neutral mod model — the
 * `GenerateModMetadataTask` input emitters consume. Section data stays
 * under [sections] keyed by loader name.
 */
data class ModMetadata(
    val id: String?,
    val name: String?,
    val version: String?,
    val description: String?,
    val authors: List<String>,
    val license: String?,
    val url: String?,
    val icon: String?,
    val environment: String?,
    /** `modid -> version range` dependency declarations. */
    val depends: Map<String, String>,
    /** `loader name -> section` — each emitter reads its own key. */
    val sections: Map<String, LoaderSection>
) : Serializable
