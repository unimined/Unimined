package xyz.wagyourtail.unimined.remap.internal.pipeline

import xyz.wagyourtail.commons.core.logger.Logger as CommonsLogger
import xyz.wagyourtail.unimined.api.access.AccessModel
import xyz.wagyourtail.unimined.api.mapping.MappingData
import xyz.wagyourtail.unimined.api.spec.RemapSpec
import xyz.wagyourtail.unimined.remap.spi.MutableStepContext
import xyz.wagyourtail.unimined.remap.spi.PipelineState
import xyz.wagyourtail.unimined.remap.spi.RemapSurface
import xyz.wagyourtail.unimined.remap.spi.StepContext
import java.nio.file.Path

/**
 * Task-side [StepContext]: the resolved spec plus whatever the pipeline's
 * declared `needs*` flags required materializing.
 */
internal class TaskStepContext(
    override val surface: RemapSurface,
    override val spec: RemapSpec,
    /** `var` so steps may swap in an augmented [MappingData] for
     *  downstream steps (e.g. `addExtraInnerClassMappings` wraps the
     *  tree in a delta overlay) — the pipeline's `MappingData` is
     *  daemon-shared across parallel transforms, so augmentations must
     *  never mutate it in place. */
    override var mappings: MappingData?,
    override val classpath: List<Path>,
    override val workDir: Path,
    override val logger: CommonsLogger,
    override val accessModel: AccessModel?,
    override val log: CommonsLogger,
    override val state: PipelineState
) : MutableStepContext
