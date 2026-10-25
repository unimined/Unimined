package xyz.wagyourtail.unimined.remapper.uml

import kotlin.reflect.KClass
import kotlinx.coroutines.runBlocking
import org.gradle.api.GradleException
import xyz.wagyourtail.unimined.api.mapping.UmlMappingTreeView
import xyz.wagyourtail.unimined.api.mapping.view
import xyz.wagyourtail.unimined.api.spec.RemapSpec
import xyz.wagyourtail.unimined.mapping.Namespace
import xyz.wagyourtail.unimined.mapping.remapper.asm.AsmRemapper
import xyz.wagyourtail.unimined.mapping.remapper.asm.Options
import xyz.wagyourtail.unimined.remap.RemapStepSpec
import xyz.wagyourtail.unimined.remap.spi.ArtifactStep
import xyz.wagyourtail.unimined.remap.spi.RemapStepType
import xyz.wagyourtail.unimined.remap.spi.RemapSurface
import xyz.wagyourtail.unimined.remap.spi.StepContext
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists

/**
 * The `remap` step backed by `UniminedMappingLibrary`'s [AsmRemapper] —
 * binary surfaces ([RemapSurface.JAR] and the decompile fork
 * [RemapSurface.DECOMPILE]).
 *
 * Consumes mappings through the [UmlMappingTreeView] typed view, so it gets
 * the full UML tree — including constant-group unpicking
 * ([AsmRemapper.Options.unpickConstants]) and BytecodeTransform metadata —
 * which the plain `MappingVisitor` interchange cannot carry.
 *
 * Classpath entries feed the inheritance pass ([AsmRemapper.remap]'s
 * `libraries`), so member mappings propagate into subclasses the tree does
 * not list explicitly.
 */
class UmlRemapStepType : RemapStepType<RemapStepSpec> {

    override val name = "uml"
    override val stepName = "remap"
    override val specType: KClass<RemapStepSpec> = RemapStepSpec::class
    override val surfaces: Set<RemapSurface> = setOf(RemapSurface.JAR, RemapSurface.DECOMPILE)

    override val needsMappingTree = true
    override val needsClasspath = true

    override fun supports(spec: RemapSpec) = true

    override fun create(spec: RemapStepSpec, ctx: StepContext): ArtifactStep =
        ArtifactStep { input, c -> remap(input, c) }

    private fun remap(input: Path, ctx: StepContext): Path {
        val view = ctx.mappings?.view<UmlMappingTreeView>()
            ?: throw GradleException(
                "remap step 'uml' requires mapping data exposing a UmlMappingTreeView"
            )
        val spec = ctx.spec
        val target = Files.createTempDirectory(ctx.workDir, "uml")
            .resolve(input.fileName.toString())

        ctx.logger.info(
            "[remap/uml] remapping {} {} -> {}",
            input.fileName, spec.sourceNamespace, spec.targetNamespace
        )

        runBlocking {
            AsmRemapper.remap(
                input,
                target,
                listOf(view.tree),
                Namespace(spec.sourceNamespace),
                Namespace(spec.targetNamespace),
                libraries = ctx.classpath.filter { it.exists() }.toSet(),
                options = Options()
            )
        }
        return target
    }

}
