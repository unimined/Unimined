package xyz.wagyourtail.unimined.remapper.tiny

import kotlin.reflect.KClass
import net.fabricmc.tinyremapper.OutputConsumerPath
import net.fabricmc.tinyremapper.TinyRemapper
import org.gradle.api.GradleException
import xyz.wagyourtail.unimined.api.mapping.MappingTree
import xyz.wagyourtail.unimined.api.mapping.view
import xyz.wagyourtail.unimined.api.spec.RemapSpec
import xyz.wagyourtail.unimined.remap.RemapStepSpec
import xyz.wagyourtail.unimined.remap.spi.ArtifactStep
import xyz.wagyourtail.unimined.remap.spi.RemapStepType
import xyz.wagyourtail.unimined.remap.spi.RemapSurface
import xyz.wagyourtail.unimined.remap.spi.StepContext
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists

/**
 * The `remap` step backed by `net.fabricmc:tiny-remapper` — binary surface
 * only. Consumes the resolved [MappingTree] view through the
 * `MappingVisitor` interchange, so it needs no mapping readers of its own.
 *
 * Pending ports from the 1.x remapper (tracked in Docs/progress.md):
 * kotlin-metadata remapping and mixin remapping — in 2.0 these belong to
 * extension steps/decorators rather than the base remap step.
 */
class TinyRemapStepType : RemapStepType<RemapStepSpec> {

    override val name = "tiny"
    override val stepName = "remap"
    override val specType: KClass<RemapStepSpec> = RemapStepSpec::class
    override val surfaces: Set<RemapSurface> = setOf(RemapSurface.JAR)

    override val needsMappingTree = true
    override val needsClasspath = true

    override fun supports(spec: RemapSpec) = true

    /**
     * Tiny-remapper is the default binary remap engine — it wins
     * selection ties against opt-in engines like `uml`, which remain
     * reachable via an explicit `engine = "uml"` override.
     */
    override val priority: Int = 1

    override fun create(spec: RemapStepSpec, ctx: StepContext): ArtifactStep =
        ArtifactStep { input, c -> remap(input, c) }

    private fun remap(input: Path, ctx: StepContext): Path {
        val tree = ctx.mappings?.view<MappingTree>()
            ?: throw GradleException(
                "remap step 'tiny' requires mapping data exposing a MappingTree view"
            )
        val spec = ctx.spec
        // OutputConsumerPath opens the target with zipfs — it must not
        // exist yet (an empty file is not a readable zip)
        val target = Files.createTempDirectory(ctx.workDir, "tiny")
            .resolve("${input.fileName}")

        ctx.logger.info(
            "[remap/tiny] remapping {} {} -> {}",
            input.fileName, spec.sourceNamespace, spec.targetNamespace
        )

        val remapper = TinyRemapper.newRemapper()
            .withMappings { acceptor ->
                tree.accept(
                    spec.sourceNamespace,
                    spec.targetNamespace,
                    AcceptorMappingVisitor(acceptor)
                )
            }
            .skipLocalVariableMapping(true)
            .ignoreConflicts(true)
            .threads(Runtime.getRuntime().availableProcessors())
            .build()

        try {
            val classpath = ctx.classpath.filter { it.exists() && it != input }
            if (classpath.isNotEmpty()) {
                remapper.readClassPathAsync(*classpath.toTypedArray()).join()
            }
            val tag = remapper.createInputTag()
            remapper.readInputsAsync(tag, input).join()
            OutputConsumerPath.Builder(target).build().use { consumer ->
                consumer.addNonClassFiles(input, remapper, emptyList())
                remapper.apply(consumer, tag)
            }
        } catch (e: Exception) {
            target.deleteIfExists()
            throw e
        } finally {
            remapper.finish()
        }
        return target
    }

}
