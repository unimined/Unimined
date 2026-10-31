package xyz.wagyourtail.unimined.transform.loader

import org.gradle.api.artifacts.dsl.RepositoryHandler
import java.net.URI

/**
 * A repository a [LoaderDescriptor] needs — richer than a bare URL so
 * products can declare artifact-only feeds and ivy pattern-layout
 * repos (liteloader's legacy redist).
 */
sealed interface LoaderRepo {

    /** Register this repo on the project's repository handler. */
    fun registerOn(repos: RepositoryHandler)

    /** How a maven repo resolves metadata beyond Gradle's default. */
    enum class MetadataMode {
        /** Gradle default (module metadata + pom). */
        DEFAULT,
        /** POM + direct-artifact fallback — jar-only feeds like forge
         *  mavens (scala `_*_mc` FML libs) and artifact-only hosts that
         *  still carry some poms. */
        POM_AND_ARTIFACT,
        /** Artifact only — feeds with no metadata at all
         *  (dl.liteloader.com/versions). */
        ARTIFACT_ONLY
    }

    /** Maven repo — [metadata] controls non-default metadata source
     *  sets; [includeGroups] content-filters to specific groups. */
    data class Maven(
        val url: String,
        val metadata: MetadataMode = MetadataMode.DEFAULT,
        val includeGroups: Set<String> = emptySet()
    ) : LoaderRepo {
        override fun registerOn(repos: RepositoryHandler) {
            repos.maven { r ->
                r.url = URI.create(url)
                when (metadata) {
                    MetadataMode.DEFAULT -> {}
                    MetadataMode.POM_AND_ARTIFACT -> r.metadataSources { s ->
                        s.mavenPom()
                        s.artifact()
                    }
                    MetadataMode.ARTIFACT_ONLY -> r.metadataSources { s ->
                        s.artifact()
                    }
                }
                if (includeGroups.isNotEmpty()) {
                    r.content { c -> includeGroups.forEach(c::includeGroup) }
                }
            }
        }
    }

    /** Ivy pattern-layout repo — [artifactPattern] like
     *  `"[artifact]_[revision].[ext]"` for feeds that aren't maven-
     *  shaped at all. [includeModules] is `(group, module)` pairs. */
    data class Ivy(
        val url: String,
        val artifactPattern: String,
        val includeModules: Set<Pair<String, String>> = emptySet()
    ) : LoaderRepo {
        override fun registerOn(repos: RepositoryHandler) {
            repos.ivy { r ->
                r.url = URI.create(url)
                r.patternLayout { p -> p.artifact(artifactPattern) }
                if (includeModules.isNotEmpty()) {
                    r.content { c ->
                        includeModules.forEach { (g, m) -> c.includeModule(g, m) }
                    }
                }
                r.metadataSources { s -> s.artifact() }
            }
        }
    }
}
