package xyz.wagyourtail.unimined.minecraft.internal

import org.gradle.api.GradleException
import org.gradle.api.Project
import java.time.Duration

/** Parsed cache controls shared with Minecraft provider implementations. */
internal data class ProviderCacheSettings(
    val offline: Boolean,
    val forceReload: Boolean,
    val refreshDependencies: Boolean,
    val refreshDownloads: Boolean,
    val rebuildArtifacts: Boolean,
    val metadataCacheTtl: Duration?
) {

    /** Effective remote-input refresh request, including Gradle refresh. */
    val refreshUniminedDownloads: Boolean
        get() = forceReload || refreshDownloads || refreshDependencies

    /** Effective generated-artifact rebuild request. */
    val rebuildUniminedArtifacts: Boolean
        get() = forceReload || rebuildArtifacts || refreshDependencies

    companion object {

        private const val FORCE_RELOAD = "unimined.forceReload"
        private const val REFRESH_DOWNLOADS = "unimined.refreshDownloads"
        private const val REBUILD_ARTIFACTS = "unimined.rebuildArtifacts"
        private const val METADATA_TTL = "unimined.metadataCacheTtl"

        /** Read the build's Gradle flags/properties. */
        fun from(project: Project): ProviderCacheSettings = parse(
            offline = project.gradle.startParameter.isOffline,
            refreshDependencies = project.gradle.startParameter.isRefreshDependencies,
            forceReloadValue = project.providers.gradleProperty(FORCE_RELOAD).orNull,
            refreshDownloadsValue = project.providers.gradleProperty(REFRESH_DOWNLOADS).orNull,
            rebuildArtifactsValue = project.providers.gradleProperty(REBUILD_ARTIFACTS).orNull,
            metadataTtlValue = project.providers.gradleProperty(METADATA_TTL).orNull
        )

        /** Parse independently of Gradle so property semantics are testable. */
        fun parse(
            offline: Boolean,
            refreshDependencies: Boolean,
            forceReloadValue: String?,
            refreshDownloadsValue: String?,
            rebuildArtifactsValue: String?,
            metadataTtlValue: String?
        ): ProviderCacheSettings {
            val forceReload = parseBoolean(FORCE_RELOAD, forceReloadValue)
            val refreshDownloads = parseBoolean(REFRESH_DOWNLOADS, refreshDownloadsValue)
            val rebuildArtifacts = parseBoolean(REBUILD_ARTIFACTS, rebuildArtifactsValue)
            val ttl = metadataTtlValue?.let {
                try {
                    Duration.parse(it)
                } catch (e: Exception) {
                    throw GradleException(
                        "$METADATA_TTL must be an ISO-8601 duration (for example PT6H), got '$it'",
                        e
                    )
                }
            }
            require(ttl == null || !ttl.isNegative) {
                "$METADATA_TTL must not be negative"
            }
            return ProviderCacheSettings(
                offline = offline,
                forceReload = forceReload,
                refreshDependencies = refreshDependencies,
                refreshDownloads = refreshDownloads,
                rebuildArtifacts = rebuildArtifacts,
                metadataCacheTtl = ttl
            )
        }

        private fun parseBoolean(name: String, value: String?): Boolean =
            when (value?.lowercase()) {
                null, "false" -> false
                "true" -> true
                else -> throw GradleException(
                    "$name must be 'true' or 'false', got '$value'"
                )
            }

    }

}
