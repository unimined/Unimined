package xyz.wagyourtail.unimined.remapper.uml

import xyz.wagyourtail.unimined.api.mapping.view
import xyz.wagyourtail.unimined.api.spec.RemapSpec
import xyz.wagyourtail.unimined.mapping.MappingTree
import xyz.wagyourtail.unimined.mapping.MutableMappingTree
import xyz.wagyourtail.unimined.mapping.Namespace
import xyz.wagyourtail.unimined.mapping.remapper as nsBoundRemapper
import xyz.wagyourtail.unimined.mapping.sink.emit
import xyz.wagyourtail.unimined.mapping.inheritance.ASMInheritanceTree
import xyz.wagyourtail.unimined.mapping.inheritance.LazyASMInheritanceTree
import xyz.wagyourtail.unimined.mapping.remapper.asm.AsmExtensionFactory
import xyz.wagyourtail.unimined.mapping.remapper.asm.AsmRemapper
import xyz.wagyourtail.unimined.mapping.remapper.asm.Options
import xyz.wagyourtail.unimined.mapping.remapper.ext.classtransform.ClassTransformExtension
import xyz.wagyourtail.unimined.mapping.remapper.ext.metadata.KotlinMetadataExtension
import xyz.wagyourtail.unimined.mapping.remapper.ext.mixin.MixinExtension
import xyz.wagyourtail.unimined.remap.ModArtifactsStepSpec
import xyz.wagyourtail.unimined.remap.spi.ArtifactStep
import xyz.wagyourtail.unimined.remap.spi.RemapStepType
import xyz.wagyourtail.unimined.remap.spi.RemapSurface
import xyz.wagyourtail.unimined.remap.spi.StepContext
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.reflect.KClass

/**
 * The `modArtifacts` step backed by `UniminedMappingLibrary`'s
 * [AsmRemapper] — binary surface ([RemapSurface.JAR]) only.
 *
 * Mod-jar metadata survives a pure namespace remap with source-namespace
 * references still embedded: `kotlin.Metadata` class names, mixin
 * annotation refs (`method`, `target`, injection `args`), mixin config
 * paths, and ClassTransform/JarModAgent transformer refs. This step runs
 * a second ASM pass with only the mod-artifact
 * [xyz.wagyourtail.unimined.mapping.remapper.asm.AsmExtension]s
 * enabled, resolving those embedded refs through the source→target
 * tree. The class/member remap itself is run as identity
 * (target→target): the jar's classes are already target-named, and
 * re-applying the real source→target map renames any target-named
 * class that textually collides with an unrelated source-namespace
 * row (e.g. mojmap `DedicatedServer` → yarn
 * `MinecraftDedicatedServer`, while yarn has its own
 * `DedicatedServer`) — producing duplicate output entries. The
 * extensions still see the real source→target [ExtensionContext],
 * which is what their embedded-ref resolution needs.
 *
 * Engine-agnostic by construction: appended to the JAR pipeline after
 * `remap` regardless of which engine performed it (a `remap("tiny")`
 * override gets identical treatment), and to remapped dependency views,
 * so mod deps get mixin refmaps for the dev runtime. Resource-gated —
 * [MixinExtension] and [ClassTransformExtension] only load when the jar
 * carries their resources.
 */
class UmlModArtifactsStepType : RemapStepType<ModArtifactsStepSpec> {

    override val name = "uml"
    override val stepName = "modArtifacts"
    override val specType: KClass<ModArtifactsStepSpec> = ModArtifactsStepSpec::class
    override val surfaces: Set<RemapSurface> = setOf(RemapSurface.JAR)

    override val needsMappingTree = true
    override val needsClasspath = true

    override fun supports(spec: RemapSpec) = true

    override val priority: Int = 1

    override fun create(spec: ModArtifactsStepSpec, ctx: StepContext): ArtifactStep =
        ArtifactStep { input, c -> run(input, c) }

    private fun run(input: Path, ctx: StepContext): Path {
        val spec = ctx.spec
        // identity hop — nothing to rewrite; also skips emitting a
        // "refmap" key into mixin configs on identity remaps
        if (Namespace(spec.sourceNamespace) == Namespace(spec.targetNamespace)) {
            return input
        }
        val extensions = modArtifactExtensions(input, ctx)
        if (extensions.isEmpty()) return input

        val view = ctx.mappings?.view<MappingTree>()
            ?: throw org.gradle.api.GradleException(
                "modArtifacts step 'uml' requires mapping data exposing a UML MappingTree view"
            )
        val slog = ctx.log.subLogger("uml:modArtifacts")
        val target = Files.createTempDirectory(ctx.workDir, "mod-artifacts")
            .resolve(input.fileName.toString())

        slog.info("processing mod artifacts in ${input.fileName} " +
            "${spec.sourceNamespace} -> ${spec.targetNamespace}")

        val fns = Namespace(spec.sourceNamespace)
        val tns = Namespace(spec.targetNamespace)
        val classpathJars = ctx.classpath.filter { it.exists() }
        val classpathKey = classpathJars
            .map { Triple(it, Files.size(it), Files.getLastModifiedTime(it).toMillis()) }
            .sortedBy { it.first }
        val index = UmlRemapStepType.scanCache.getOrPut(classpathKey) {
            LazyASMInheritanceTree.index(classpathJars.toSet())
        }
        val inputScan = ASMInheritanceTree.scan(setOf(input))
        // merge + overlay like remapGraph so extension member lookups see
        // inheritance-propagated names; see the class doc for why the outer
        // class/member remap is identity rather than fns -> tns.
        val merged = MutableMappingTree(view.namespaces.toSet())
        view.emit(merged)
        val overlaid = AsmRemapper.overlay(
            merged,
            LazyASMInheritanceTree(fns, inputScan, index)
        )
        AsmRemapper.writeJar(
            target,
            AsmRemapper.remap(
                input,
                view.nsBoundRemapper(tns, tns),
                overlaid,
                fns,
                tns,
                options = Options(extensions = extensions)
            )
        )
        ctx.state.currentNamespace = spec.targetNamespace
        return target
    }

    /**
     * Extension set for a JAR-surface mod artifact:
     * `kotlin.Metadata` remap (any Kotlin mod jar — undetectable without
     * parsing classes, so unconditional on this surface), mixin config
     * rewrite + refmap emission (jars with `*.mixins.json`), and
     * JarModAgent refmap emission (jars whose manifest declares
     * `JarModAgent-Refmaps`). All three are non-`perClass`
     * [xyz.wagyourtail.unimined.mapping.remapper.asm.AsmExtension]s —
     * safe here because this step only runs on (small) mod jars, never
     * on the MC artifact's streaming decompile pipeline.
     */
    private fun modArtifactExtensions(
        input: Path, ctx: StepContext
    ): List<AsmExtensionFactory> {
        val exts = mutableListOf<AsmExtensionFactory>()
        exts += AsmExtensionFactory { c -> KotlinMetadataExtension(c) }
        java.util.zip.ZipFile(input.toFile()).use { zip ->
            if (zip.entries().asSequence().any {
                    it.name.endsWith(".mixins.json")
                }) {
                exts += AsmExtensionFactory { c -> MixinExtension(c) }
            }
            zip.getEntry("META-INF/MANIFEST.MF")?.let { e ->
                val manifest = java.util.jar.Manifest(
                    zip.getInputStream(e))
                val refmaps = manifest.mainAttributes
                    .getValue("JarModAgent-Refmaps")
                if (!refmaps.isNullOrBlank()) {
                    // manifest lists pathSeparator-separated refmap
                    // resources — emit onto the first declared path
                    val refmapPath = refmaps
                        .split(java.io.File.pathSeparatorChar)
                        .first().trim()
                        .ifEmpty { "jarmodagent.refmap.json" }
                    exts += AsmExtensionFactory { c ->
                        ClassTransformExtension(c, refmapPath = refmapPath)
                    }
                }
            }
        }
        return exts
    }

}
