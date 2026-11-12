package xyz.wagyourtail.unimined.remapper.uml

import kotlinx.coroutines.runBlocking
import xyz.wagyourtail.unimined.api.mapping.view
import xyz.wagyourtail.unimined.api.spec.RemapSpec
import xyz.wagyourtail.unimined.mapping.MappingTree
import xyz.wagyourtail.unimined.mapping.Namespace
import xyz.wagyourtail.unimined.mapping.remapper as nsBoundRemapper
import xyz.wagyourtail.unimined.mapping.inheritance.ASMInheritanceTree
import xyz.wagyourtail.unimined.mapping.inheritance.LazyASMInheritanceTree
import xyz.wagyourtail.unimined.mapping.remapper.asm.AsmExtensionFactory
import xyz.wagyourtail.unimined.mapping.remapper.asm.AsmRemapper
import xyz.wagyourtail.unimined.mapping.remapper.asm.Options
import xyz.wagyourtail.unimined.mapping.remapper.ext.classtransform.ClassTransformExtension
import xyz.wagyourtail.unimined.mapping.remapper.ext.metadata.KotlinMetadataExtension
import xyz.wagyourtail.unimined.remapper.uml.ext.LiteloaderObfTableExtension
import xyz.wagyourtail.unimined.mapping.remapper.ext.mixin.MixinExtension
import xyz.wagyourtail.unimined.mapping.remapper.ext.mixin.MixinTables
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
 *
 * The extension [ExtensionContext] rides
 * [xyz.wagyourtail.unimined.mapping.tree.impl.ResolvingMappingTree] over
 * the shared [xyz.wagyourtail.unimined.mapping.remapper.asm.RemapBatch]
 * state — propagated member names resolve through the same member-group
 * graph the remap wrote with, instead of materializing a propagated copy
 * of the merged tree per jar.
 */
class UmlModArtifactsStepType : RemapStepType<ModArtifactsStepSpec> {

    override val name = "uml"
    override val stepName = "modArtifacts"
    override val specType: KClass<ModArtifactsStepSpec> = ModArtifactsStepSpec::class
    override val surfaces: Set<RemapSurface> = setOf(RemapSurface.JAR)

    override val needsMappingTree = true
    override val needsClasspath = true
    // consumed via the shared batch's access-model overlay — keeps this
    // step's BatchKey identical to the remap step's so a preceding
    // uml:remap's prepared batch is reused instead of rebuilt
    override val needsAccessModel = true

    override fun supports(spec: RemapSpec) = true

    override val priority: Int = 1

    override fun create(spec: ModArtifactsStepSpec, ctx: StepContext): ArtifactStep =
        ArtifactStep { input, c -> run(input, c, spec) }

    private fun run(input: Path, ctx: StepContext, stepSpec: ModArtifactsStepSpec): Path {
        val spec = ctx.spec
        // identity hop — nothing to rewrite; also skips emitting a
        // "refmap" key into mixin configs on identity remaps
        if (Namespace(spec.sourceNamespace) == Namespace(spec.targetNamespace)) {
            return input
        }
        val extensions = modArtifactExtensions(input, ctx, stepSpec)
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
        // same alias partition as UmlRemapStepType — identical
        // classpathKey/scanAliasKey derivation keeps this step's
        // BatchKey equal to the remap step's so the prepared batch
        // is shared rather than rebuilt
        val (sameNsJars, foreignAliases) = ctx.spec.scanAliases
            .partition { Namespace(it.sourceNs) == fns }
        val classpathJars = (ctx.classpath + sameNsJars.map { it.jar })
            .filter { it.exists() }
        val classpathKey = classpathJars
            .map { Triple(it, Files.size(it), Files.getLastModifiedTime(it).toMillis()) }
            .sortedBy { it.first }
        val scanAliasKey = foreignAliases.map {
            UmlRemapStepType.Companion.ScanAliasKey(
                Triple(it.jar, Files.size(it.jar), Files.getLastModifiedTime(it.jar).toMillis()),
                it.sourceNs,
                Triple(it.mappingsFile, Files.size(it.mappingsFile),
                    Files.getLastModifiedTime(it.mappingsFile).toMillis())
            )
        }
        // computeIfAbsent like the remap step — getOrPut's non-atomic
        // get-then-put races N first-access transforms into N parallel
        // index builds of the same classpath
        val index = UmlRemapStepType.scanCache.computeIfAbsent(classpathKey) {
            LazyASMInheritanceTree.index(classpathJars.toSet())
        }
        // Shared remap batch — identical keying to UmlRemapStepType, so
        // a preceding uml:remap's merged tree + propagated member groups
        // + classpath graph are reused verbatim instead of re-emitted
        // per jar. After another engine's remap the cache misses and a
        // batch is prepared here (same trees the uml step would use).
        val batch = UmlRemapStepType.remapBatch(
            UmlRemapStepType.Companion.BatchKey(
                System.identityHashCode(ctx.mappings), classpathKey,
                scanAliasKey,
                fns, tns, ctx.accessModel?.serialize()?.hashCode() ?: 0
            )
        ) {
            slog.lifecycle("preparing shared remap batch " +
                "(${classpathJars.size}-jar classpath, " +
                "${foreignAliases.size} scan aliases) for $fns -> $tns")
            // foreign-namespace classpath members (e.g. the official
            // MC jar on an intermediary batch) fold in as translated
            // scans — see UmlRemapStepType.translatedScan
            val extraScans = foreignAliases.map {
                UmlRemapStepType.translatedScan(it, fns, slog)
            }
            // the remap-analysis overlay rides the batch's merged tree —
            // names are overlay-invariant so member resolution matches
            // either way, but sharing keeps one batch per key
            val overlay = ctx.accessModel
                ?.entries(spec.sourceNamespace, view)
                ?.takeUnless { it.isEmpty() }
                ?.let { accessModelOverlay(it, fns, view) }
            runBlocking {
                AsmRemapper.prepareBatch(
                    listOf(view) + listOfNotNull(overlay),
                    fns, tns, index = index,
                    extraScans = extraScans
                )
            }
        }
        val inputScan = ASMInheritanceTree.scan(setOf(input))
        // lazy propagated-member view over the batch state — extension
        // member lookups resolve through the same MemberGroups +
        // GraphNamespaceRemapper the remap wrote with, without IBT's
        // O(rows) propagating re-emit per jar (the ~500k-row
        // materialization that dominated this step). The input's own
        // classes fold into the shared graph so members declared on the
        // mod jar resolve too.
        val overlaid = batch.mappingView(inputScan)
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
     * rewrite + refmap emission (jars with `*.mixins.json`), pre-mixin
     * LiteLoader baked name-table rewrite (jars carrying
     * `com/mumfrey/liteloader/core/runtime/Obf` or `litemod.json`), and
     * JarModAgent refmap emission (jars whose manifest declares
     * `JarModAgent-Refmaps`). All four are non-`perClass`
     * [xyz.wagyourtail.unimined.mapping.remapper.asm.AsmExtension]s —
     * safe here because this step only runs on (small) mod jars, never
     * on the MC artifact's streaming decompile pipeline.
     */
    private fun modArtifactExtensions(
        input: Path, ctx: StepContext, spec: ModArtifactsStepSpec
    ): List<AsmExtensionFactory> {
        val exts = mutableListOf<AsmExtensionFactory>()
        exts += AsmExtensionFactory { c -> KotlinMetadataExtension(c) }
        // `modArtifacts { off() }` — 1.x mixinRemap.off parity: the
        // mixin/JMA/liteloader annotation machinery drops entirely
        // (kotlin.Metadata stays — it isn't mixin-scoped)
        if (spec.mixinRemapOff) return exts
        val force = spec.forceMixinTables
        java.util.zip.ZipFile(input.toFile()).use { zip ->
            val mixinForced = force.any { it == "mixin" || it == "mixinextras" }
            if (mixinForced || zip.entries().asSequence().any {
                    MixinExtension.isMixinConfigPath(it.name)
                }) {
                // empty force = auto table (ALL — both standard and
                // extras); a force names the explicit table set
                val table = when {
                    "mixin" in force && "mixinextras" in force ->
                        MixinTables.ALL
                    "mixinextras" in force -> MixinTables.EXTRAS
                    "mixin" in force -> MixinTables.STANDARD
                    else -> MixinTables.ALL
                }
                exts += AsmExtensionFactory { c ->
                    MixinExtension(
                        c, table, spec.emitRefmap, spec.noRefmapConfigs)
                }
            }
            if (zip.getEntry(
                    "com/mumfrey/liteloader/core/runtime/Obf.class") != null ||
                // LiteMod jars carry @Obfuscated overlay tables but not the
                // loader's own Obf runtime class — the litemod.json marker
                // catches them
                zip.getEntry("litemod.json") != null) {
                exts += AsmExtensionFactory { c ->
                    LiteloaderObfTableExtension(c)
                }
            }
            var refmapPath: String? = null
            zip.getEntry("META-INF/MANIFEST.MF")?.let { e ->
                val manifest = java.util.jar.Manifest(
                    zip.getInputStream(e))
                val refmaps = manifest.mainAttributes
                    .getValue("JarModAgent-Refmaps")
                if (!refmaps.isNullOrBlank()) {
                    // the agent splits manifest attributes on a single
                    // space (JarModder) — emit onto the first declared
                    // path; the agent merges all refmaps per transformer
                    // anyway, so one canonical path carries the result
                    refmapPath = refmaps
                        .split(" ")
                        .firstOrNull { it.isNotBlank() }
                }
            }
            if ("jma" in force || refmapPath != null) {
                val path = refmapPath ?: "jarmodagent.refmap.json"
                exts += AsmExtensionFactory { c ->
                    ClassTransformExtension(
                        c, refmapPath = path, emitRefmap = spec.emitRefmap)
                }
            }
        }
        return exts
    }

}
