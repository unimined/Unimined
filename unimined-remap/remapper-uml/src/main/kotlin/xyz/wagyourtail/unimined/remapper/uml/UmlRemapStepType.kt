package xyz.wagyourtail.unimined.remapper.uml

import kotlinx.coroutines.runBlocking
import org.gradle.api.GradleException
import xyz.wagyourtail.unimined.api.mapping.view
import xyz.wagyourtail.unimined.api.spec.RemapSpec
import xyz.wagyourtail.unimined.api.util.ProgressGate
import xyz.wagyourtail.unimined.mapping.MappingTree
import xyz.wagyourtail.unimined.mapping.Namespace
import xyz.wagyourtail.unimined.mapping.inheritance.ASMInheritanceTree
import xyz.wagyourtail.unimined.mapping.inheritance.LazyASMInheritanceTree
import xyz.wagyourtail.unimined.mapping.jvms.four.two.one.InternalName
import xyz.wagyourtail.unimined.mapping.remapper.asm.AsmRemapper
import xyz.wagyourtail.unimined.mapping.remapper.asm.Options
import xyz.wagyourtail.unimined.mapping.remapper.asm.PackageAccess
import xyz.wagyourtail.unimined.remap.RemapStepSpec
import xyz.wagyourtail.unimined.remap.spi.ArtifactStep
import xyz.wagyourtail.unimined.remap.spi.RemapStepType
import xyz.wagyourtail.unimined.remap.spi.RemapSurface
import xyz.wagyourtail.unimined.remap.spi.StepContext
import java.nio.file.Files
import java.nio.file.Path
import kotlin.collections.getOrPut
import kotlin.io.path.exists
import kotlin.reflect.KClass

/**
 * The `remap` step backed by `UniminedMappingLibrary`'s [AsmRemapper] —
 * binary surfaces ([RemapSurface.JAR] and the decompile fork
 * [RemapSurface.DECOMPILE]).
 *
 * Consumes mappings through the [UML MappingTree view] typed view, so it gets
 * the full UML tree — including constant-group unpicking
 * ([AsmRemapper.Options.unpickConstants]) and BytecodeTransform metadata —
 * which a row-only bundle cannot carry.
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
    override val needsAccessModel = true

    override fun supports(spec: RemapSpec) = true

    /**
     * UML's [AsmRemapper] is the default binary remap engine — the
     * `tiny` engine remains reachable via an explicit `engine = "tiny"`
     * override and stays for engine-vs-engine output verification.
     */
    override val priority: Int = 1

    override fun create(spec: RemapStepSpec, ctx: StepContext): ArtifactStep =
        ArtifactStep { input, c -> remap(input, c) }

    private fun remap(input: Path, ctx: StepContext): Path {
        val view = ctx.mappings?.view<MappingTree>()
            ?: throw GradleException(
                "remap step 'uml' requires mapping data exposing a UML MappingTree view"
            )
        val spec = ctx.spec
        val target = Files.createTempDirectory(ctx.workDir, "uml")
            .resolve(input.fileName.toString())

        val slog = ctx.log.subLogger("uml:remap")
        slog.info(
            "remapping ${input.fileName} ${spec.sourceNamespace} -> ${spec.targetNamespace}"
        )

        // remap-analysis overlay: AW/AT/CT rules declared by the batch
        // ride the trees list as a synthetic AccessElement-bearing layer,
        // folded into the inheritance scan before propagation
        val fns = Namespace(spec.sourceNamespace)
        val referenceTree = ctx.mappings?.view<MappingTree>()
        val overlay = ctx.accessModel
            ?.entries(spec.sourceNamespace, referenceTree)
            ?.takeUnless { it.isEmpty() }
            ?.let { accessModelOverlay(it, fns, referenceTree) }

        // the mod-view classpath is identical across a batch's transforms
        // (sibling mod jars + the source-ns MC artifact) — its class→jar
        // index is immutable data, so it is cached per jar-set fingerprint
        // daemon-wide; the lazy tree only parses classpath classes that
        // hierarchy-resolution actually touches (unrelated libs — most of
        // the classpath — are never opened); propagation mutates ClassInfo,
        // so each remap gets a fresh tree over the shared index + this
        // input's own eager scan
        val classpathJars = ctx.classpath.filter { it.exists() }
        val classpathKey = classpathJars
            .map { Triple(it, Files.size(it), Files.getLastModifiedTime(it).toMillis()) }
            .sortedBy { it.first }
        val index = scanCache.getOrPut(classpathKey) {
            slog.lifecycle(
                "indexing classpath (${classpathJars.size} jars) for lazy inheritance"
            )
            LazyASMInheritanceTree.index(classpathJars.toSet())
        }
        val inputScan = ASMInheritanceTree.scan(setOf(input))

        // inline progress — the callback fires per class from UML's parallel
        // workers; the gate turns it into a throttled lifecycle line so the
        // log shows movement instead of a multi-second stall
        var phase = "remap"
        var total = 0
        val gate = ProgressGate { elapsed, done ->
            slog.info {
                "${input.fileName} ${spec.sourceNamespace}->${spec.targetNamespace}: " +
                        "$phase $done/$total classes " +
                        "(${done * 100 / total.coerceAtLeast(1)}%) in ${elapsed}ms"
            }
        }
        runBlocking {
            AsmRemapper.remap(
                input,
                target,
                listOf(view) + listOfNotNull(overlay),
                fns,
                Namespace(spec.targetNamespace),
                LazyASMInheritanceTree(fns, inputScan, index),
                // FIX — package-private members of classes whose package
                // the mappings relocate get widened (tiny's
                // checkPackageAccess+fixPackageAccess equivalent)
                options = Options(
                    packageAccess = PackageAccess.FIX,
                    onProgress = { p, d, t -> phase = p; total = t; gate.tickTo(d.toLong()) }
                )
            )
        }
        return target
    }

    private companion object {
        /** Daemon-lifetime classpath-index cache — keyed on the ordered
         *  jar-path set (with size+mtime so same-path rebuilds still
         *  re-index), so all transforms sharing a view classpath share
         *  one listing. */
        val scanCache = java.util.concurrent.ConcurrentHashMap<
                List<Triple<Path, Long, Long>>, Map<InternalName, Path>>()
    }

}
