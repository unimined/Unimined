package xyz.wagyourtail.unimined.remapper.tiny

import net.fabricmc.tinyremapper.IMappingProvider
import net.fabricmc.tinyremapper.OutputConsumerPath
import net.fabricmc.tinyremapper.TinyRemapper
import org.gradle.api.GradleException
import xyz.wagyourtail.unimined.api.mapping.MappingBundle
import xyz.wagyourtail.unimined.api.mapping.view
import xyz.wagyourtail.unimined.api.spec.RemapSpec
import xyz.wagyourtail.unimined.mapping.MappingTree
import xyz.wagyourtail.unimined.remap.RemapStepSpec
import xyz.wagyourtail.unimined.remap.spi.ArtifactStep
import xyz.wagyourtail.unimined.remap.spi.RemapStepType
import xyz.wagyourtail.unimined.remap.spi.RemapSurface
import xyz.wagyourtail.unimined.remap.spi.StepContext
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.reflect.KClass

/**
 * The `remap` step backed by `net.fabricmc:tiny-remapper` — binary surface
 * only. Consumes the resolved [MappingTree] view through the
 * `MappingBundle` rows, so it needs no mapping readers of its own.
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
    override val needsAccessModel = true

    override fun supports(spec: RemapSpec) = true

    /**
     * Tiny-remapper remains for output verification — `uml` is the
     * default engine; reach this one via an explicit
     * `engine = "tiny"` override.
     */

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

        val slog = ctx.log.subLogger("tiny:remap")
        slog.info(
            "remapping ${input.fileName} ${spec.sourceNamespace} -> ${spec.targetNamespace}"
        )

        // remap-analysis overlay: AW/AT/CT rules declared by the batch,
        // in the source namespace, so widened members propagate
        val accessRules = ctx.accessModel?.rules(spec.sourceNamespace, tree)
            ?.takeUnless { it.isEmpty }

        val remapper = TinyRemapper.newRemapper()
            .withMappings { acceptor ->
                // the bundle rows are tiny's native interchange — same
                // extraction the serialized-bundle path produces
                val bundle = MappingBundle.capture(
                    spec.sourceNamespace, spec.targetNamespace, tree
                )
                for (row in bundle.rows) {
                    when (row.kind) {
                        MappingBundle.Kind.CLASS ->
                            acceptor.acceptClass(row.srcName!!, row.dstName!!)

                        MappingBundle.Kind.FIELD -> acceptor.acceptField(
                            IMappingProvider.Member(
                                row.srcClass!!, row.srcName!!, row.srcDesc
                            ), row.dstName!!
                        )

                        MappingBundle.Kind.METHOD -> {
                            if (row.srcDesc == null) {
                                slog.warning(
                                    "null src desc: ${row.srcClass}.${row.srcName}${row.srcDesc} -> ${row.dstName}"
                                )
                            }
                            acceptor.acceptMethod(
                                IMappingProvider.Member(
                                    row.srcClass!!, row.srcName!!, row.srcDesc
                                ), row.dstName!!
                            )
                        }

                        MappingBundle.Kind.METHOD_ARG -> acceptor.acceptMethodArg(
                            IMappingProvider.Member(
                                row.srcClass!!, row.srcName!!, row.srcDesc
                            ), row.index!!, row.dstName!!
                        )

                        MappingBundle.Kind.METHOD_VAR -> acceptor.acceptMethodVar(
                            IMappingProvider.Member(
                                row.srcClass!!, row.srcName!!, row.srcDesc
                            ), row.index!!, -1, -1, row.dstName!!
                        )

                        MappingBundle.Kind.PACKAGE -> {}
                    }
                }
            }
            .skipLocalVariableMapping(true)
            .ignoreConflicts(true)
            // mappings may relocate a class across packages while leaving
            // package-private members/inner classes unmapped — widen their
            // access instead of emitting IllegalAccessError-prone output
            .checkPackageAccess(true)
            .fixPackageAccess(true)
            .threads(Runtime.getRuntime().availableProcessors())
            .also { builder ->
                accessRules?.let {
                    builder.extraAnalyzeVisitor(AccessModelAnalyzeVisitorProvider(it))
                }
            }
            .build()

        try {
            val classpath = ctx.classpath.filter { it.exists() && it != input }
            if (classpath.isNotEmpty()) {
                slog.debug(
                    "${input.fileName} analysis classpath: ${classpath.size} entries, " +
                            "${classpath.sumOf { Files.size(it) } / 1024} KB"
                )
                val t0 = System.nanoTime()
                remapper.readClassPathAsync(*classpath.toTypedArray()).join()
                slog.debug(
                    "${input.fileName} classpath analysis in ${(System.nanoTime() - t0) / 1_000_000}ms"
                )
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
