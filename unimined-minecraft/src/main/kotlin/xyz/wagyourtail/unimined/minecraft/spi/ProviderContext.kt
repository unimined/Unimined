package xyz.wagyourtail.unimined.minecraft.spi

import org.gradle.api.artifacts.dsl.DependencyFactory
import org.gradle.api.file.ProjectLayout
import org.gradle.api.logging.Logger
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ProviderFactory
import org.gradle.process.ExecOperations
import java.time.Duration

/**
 * Services made available to a [MinecraftProvider] at resolve time.
 * Carries no `Project`/`Task` — only the config-cache-safe service
 * surface providers need.
 */
interface ProviderContext {

    val layout: ProjectLayout

    val objects: ObjectFactory

    /** Creates dependency notation objects without exposing a [Project]. */
    val dependencyFactory: DependencyFactory

    val providers: ProviderFactory

    /** For providers that shell out to external tools at resolution or
     *  execution time (mavenizer CLI, NFRT, forge installers). */
    val exec: ExecOperations

    val logger: Logger

    /** Whether Gradle was invoked with `--offline`. */
    val offline: Boolean
        get() = false

    /**
     * Legacy broad cache switch. `-Punimined.forceReload=true` requests
     * both download refresh and generated-artifact rebuild.
     */
    val forceReload: Boolean
        get() = false

    /** Gradle's `--refresh-dependencies` flag, kept distinct from the
     *  Unimined-specific force-reload property. */
    val refreshDependencies: Boolean
        get() = false

    /** `-Punimined.refreshDownloads=true`: refresh remote jars/metadata. */
    val refreshDownloads: Boolean
        get() = false

    /** `-Punimined.rebuildArtifacts=true`: rebuild Unimined-derived
     *  artifacts. Gradle task actions still follow Gradle up-to-date
     *  semantics; use `--rerun-tasks` to force those actions. */
    val rebuildArtifacts: Boolean
        get() = false

    /** Effective remote-input invalidation request. */
    val refreshUniminedDownloads: Boolean
        get() = forceReload || refreshDownloads || refreshDependencies

    /** Effective generated-artifact invalidation request. */
    val rebuildUniminedArtifacts: Boolean
        get() = forceReload || rebuildArtifacts || refreshDependencies

    /**
     * Optional override for metadata-cache freshness from
     * `-Punimined.metadataCacheTtl=<ISO-8601 duration>`.
     */
    val metadataCacheTtl: Duration?
        get() = null

}
