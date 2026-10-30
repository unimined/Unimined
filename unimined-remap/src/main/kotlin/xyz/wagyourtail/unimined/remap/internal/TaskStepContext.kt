package xyz.wagyourtail.unimined.remap.internal

import org.gradle.api.logging.Logger
import xyz.wagyourtail.commons.core.logger.Logger as CommonsLogger
import xyz.wagyourtail.unimined.api.access.AccessModel
import xyz.wagyourtail.unimined.api.mapping.MappingData
import xyz.wagyourtail.unimined.api.spec.RemapSpec
import xyz.wagyourtail.unimined.remap.spi.PipelineState
import xyz.wagyourtail.unimined.remap.spi.StepContext
import java.nio.file.Path

/**
 * Task-side [StepContext]: the resolved spec plus whatever the pipeline's
 * declared `needs*` flags required materializing.
 */
internal class TaskStepContext(
    override val spec: RemapSpec,
    override val mappings: MappingData?,
    override val classpath: List<Path>,
    override val workDir: Path,
    override val logger: Logger,
    override val accessModel: AccessModel?,
    override val log: CommonsLogger,
    override val state: PipelineState
) : StepContext
