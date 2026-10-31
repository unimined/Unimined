package xyz.wagyourtail.unimined.remapper.uml

import org.gradle.api.GradleException
import xyz.wagyourtail.unimined.api.mapping.view
import xyz.wagyourtail.unimined.api.spec.RemapSpec
import xyz.wagyourtail.unimined.mapping.MappingTree
import xyz.wagyourtail.unimined.mapping.Namespace
import xyz.wagyourtail.unimined.mapping.remapper.source.SourceOptions
import xyz.wagyourtail.unimined.mapping.remapper.source.SourceRemapper
import xyz.wagyourtail.unimined.mapping.remapper.source.psi.AccessSourceExtension
import xyz.wagyourtail.unimined.mapping.remapper.source.psi.ConstantGroupSourceExtension
import xyz.wagyourtail.unimined.mapping.remapper.source.psi.InterfaceInjectionExtension
import xyz.wagyourtail.unimined.mapping.remapper.source.psi.JavadocExtension
import xyz.wagyourtail.unimined.mapping.remapper.source.psi.Jsr308Extension
import xyz.wagyourtail.unimined.remap.RemapStepSpec
import xyz.wagyourtail.unimined.remap.spi.ArtifactStep
import xyz.wagyourtail.unimined.remap.spi.RemapStepType
import xyz.wagyourtail.unimined.remap.spi.RemapSurface
import xyz.wagyourtail.unimined.remap.spi.StepContext
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.reflect.KClass

/**
 * The `remap` step for [RemapSurface.SOURCES] — UML's PSI-driven
 * [SourceRemapper] over a sources jar. Renames members through the
 * environment mapping data with all UML source extensions enabled:
 * javadoc, access-flag propagation, constant groups and interface
 * injection — matching the plan's
 * `remap("uml") // remap + javadoc-from-mappings` contract.
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

    override fun create(spec: RemapStepSpec, ctx: StepContext): ArtifactStep {
        val jsr308 = spec.jsr308ToJetbrains
        return ArtifactStep { input, c -> remap(input, c, jsr308) }
    }

    private fun remap(input: Path, ctx: StepContext, jsr308: Boolean): Path {
        val view = ctx.mappings?.view<MappingTree>()
            ?: throw GradleException(
                "remap step 'uml' (sources) requires mapping data exposing a UML MappingTree view"
            )
        val spec = ctx.spec
        val target = Files.createTempDirectory(ctx.workDir, "uml-src")
            .resolve(input.fileName.toString())

        ctx.log.subLogger("uml:sources").info(
            "remapping sources ${input.fileName} ${spec.sourceNamespace} -> ${spec.targetNamespace}"
        )

        SourceRemapper.remap(
            input,
            target,
            view,
            Namespace(spec.sourceNamespace),
            Namespace(spec.targetNamespace),
            classpath = ctx.classpath.filter { it.exists() },
            options = SourceOptions(
                extensions = buildList {
                    add(JavadocExtension())
                    add(AccessSourceExtension())
                    add(ConstantGroupSourceExtension())
                    add(InterfaceInjectionExtension())
                    if (jsr308) add(Jsr308Extension())
                }
            )
        )
        return target
    }

}
