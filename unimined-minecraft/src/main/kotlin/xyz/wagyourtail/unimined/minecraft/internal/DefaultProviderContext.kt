package xyz.wagyourtail.unimined.minecraft.internal

import org.gradle.api.file.ProjectLayout
import org.gradle.api.artifacts.dsl.DependencyFactory
import org.gradle.api.logging.Logger
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ProviderFactory
import org.gradle.process.ExecOperations
import xyz.wagyourtail.unimined.minecraft.spi.ProviderContext
import java.time.Duration

/**
 * Extension-side [ProviderContext]: the injected service surface handed to
 * providers at resolve time. Deliberately carries no `Project`.
 */
class DefaultProviderContext(
    override val layout: ProjectLayout,
    override val objects: ObjectFactory,
    override val dependencyFactory: DependencyFactory,
    override val providers: ProviderFactory,
    override val exec: ExecOperations,
    override val logger: Logger,
    override val offline: Boolean,
    override val forceReload: Boolean,
    override val refreshDependencies: Boolean,
    override val refreshDownloads: Boolean,
    override val rebuildArtifacts: Boolean,
    override val metadataCacheTtl: Duration?
) : ProviderContext
