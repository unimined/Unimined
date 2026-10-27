package xyz.wagyourtail.unimined.remapper.uml

import kotlin.reflect.KClass
import org.gradle.api.GradleException
import xyz.wagyourtail.unimined.api.mapping.UmlMappingTreeView
import xyz.wagyourtail.unimined.api.mapping.view
import xyz.wagyourtail.unimined.api.spec.RemapSpec
import xyz.wagyourtail.unimined.mapping.Namespace
import xyz.wagyourtail.unimined.mapping.remapper.source.SourceOptions
import xyz.wagyourtail.unimined.mapping.remapper.source.SourceRemapper
import xyz.wagyourtail.unimined.mapping.remapper.source.psi.JavadocExtension
import xyz.wagyourtail.unimined.remap.RemapStepSpec
import xyz.wagyourtail.unimined.remap.spi.ArtifactStep
import xyz.wagyourtail.unimined.remap.spi.RemapStepType
import xyz.wagyourtail.unimined.remap.spi.RemapSurface
import xyz.wagyourtail.unimined.remap.spi.StepContext
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists

/**
 * The `remap` step for [RemapSurface.SOURCES] — UML's PSI-driven
 * [SourceRemapper] over a sources jar. Renames members through the
 * environment mapping tree and attaches javadoc from the mapping data
 * ([JavadocExtension] is part of this step, matching the plan's
 * `remap("uml") // remap + javadoc-from-mappings` contract).
 *
 * The resolved classpath goes on the engine's resolution classpath so
 * member access resolution is exact.
 */
class UmlSourceRemapStepType : RemapStepType<RemapStepSpec> {

    override val name = "uml"
    override val stepName = "remap"
    override val specType: KClass<RemapStepSpec> = RemapStepSpec::class
    override val surfaces: Set<RemapSurface> = setOf(RemapSurface.SOURCES)

    override val needsMappingTree = true
    override val needsClasspath = true

    override fun supports(spec: RemapSpec) = true

    override fun create(spec: RemapStepSpec, ctx: StepContext): ArtifactStep =
        ArtifactStep { input, c -> remap(input, c) }

    private fun remap(input: Path, ctx: StepContext): Path {
        val view = ctx.mappings?.view<UmlMappingTreeView>()
            ?: throw GradleException(
                "remap step 'uml' (sources) requires mapping data exposing a UmlMappingTreeView"
            )
        val spec = ctx.spec
        val target = Files.createTempDirectory(ctx.workDir, "uml-src")
            .resolve(input.fileName.toString())

        ctx.logger.info(
            "[remap/uml:sources] remapping sources {} {} -> {}",
            input.fileName, spec.sourceNamespace, spec.targetNamespace
        )

        SourceRemapper.remap(
            input,
            target,
            view.tree,
            Namespace(spec.sourceNamespace),
            Namespace(spec.targetNamespace),
            classpath = ctx.classpath.filter { it.exists() },
            options = SourceOptions(extensions = listOf(JavadocExtension()))
        )
        return target
    }

}
