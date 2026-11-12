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
import xyz.wagyourtail.unimined.mapping.remapper
import xyz.wagyourtail.unimined.mapping.remapper.asm.BuiltinAsmExtensions
import xyz.wagyourtail.unimined.mapping.remapper.asm.Jsr305AsmExtension
import xyz.wagyourtail.unimined.mapping.remapper.asm.Options
import xyz.wagyourtail.unimined.mapping.remapper.asm.PackageAccess
import xyz.wagyourtail.unimined.mapping.remapper.asm.RemapBatch
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
        ArtifactStep { input, c ->
            remap(input, c, spec.jsr305ToJetbrains, spec.fixInnerClasses)
        }

    private fun remap(
        input: Path, ctx: StepContext,
        jsr305: Boolean, fixInnerClasses: Boolean?
    ): Path {
        val spec = ctx.spec
        val slog = ctx.log.subLogger("uml:remap")

        // the jar's live namespace — remap steps publish their output ns
        // onto state.currentNamespace, so a chained pipeline validates
        // against the post-remap truth, not the stale declared source
        val fns = Namespace(ctx.state.currentNamespace)
        val tns = Namespace(spec.targetNamespace)

        // identity hop — nothing to rename; skip the classpath index +
        // full ASM pass (pure copy). Identity envs historically ran no
        // transform at all, so remap-pass transforms (jsr305, invalid-
        // local renames, package-access fixes) are equally no-ops here:
        // with no relocations there is nothing for them to rewrite.
        if (fns == tns) {
            ctx.state.currentNamespace = spec.targetNamespace
            return input
        }

        val view = ctx.mappings?.view<MappingTree>()
            ?: throw GradleException(
                "remap step 'uml' requires mapping data exposing a UML MappingTree view"
            )
        val target = Files.createTempDirectory(ctx.workDir, "uml")
            .resolve(input.fileName.toString())

        slog.info(
            "remapping ${input.fileName} ${spec.sourceNamespace} -> ${spec.targetNamespace}"
        )

        val referenceTree = ctx.mappings?.view<MappingTree>()

        // fail loudly when the input jar is in the TARGET namespace
        // already — a wrong-layer artifact upstream — but pass through
        // jars foreign to the tree entirely (non-MC library deps like
        // realms/authlib carry zero mapped classes; the identity remap
        // they produce is correct)
        val probe = xyz.wagyourtail.unimined.api.util.Perf.span(
            "uml:remap.probe", "input" to input.fileName.toString()
        ) {
            java.util.zip.ZipFile(input.toFile()).use { z ->
                val names = z.entries().asSequence()
                    .filter { !it.isDirectory && it.name.endsWith(".class") }
                    .map { it.name.removeSuffix(".class") }
                    .toList()
                // stride-sample — merged/unmapped classes legitimately
                // absent from the tree cluster at the front of the jar
                val stride = (names.size / 64).coerceAtLeast(1)
                names.filterIndexed { i, _ -> i % stride == 0 }
            }
        }
        // skip when the fns layer carries no classes at all (nothing to
        // validate against) or the jar is too small to infer a mismatch
        // — a handful of legitimately-unmapped classes looks identical
        // to a mislabeled layer; ~64-sample jars can't all be unmapped
        val fnsPopulated = view.classes.any { it.names.containsKey(fns) }
        if (fnsPopulated && probe.size >= 8 &&
            probe.none { view.getClass(fns, InternalName.unchecked(it)) != null }
        ) {
            check(probe.none {
                view.getClass(tns, InternalName.unchecked(it)) != null
            }) {
                "uml:remap ${input.fileName}: none of ${probe.size} sampled " +
                    "classes exist in namespace " +
                    "'${ctx.state.currentNamespace}' — the input jar is in a " +
                    "different namespace"
            }
        }
        // classes the tree says SHOULD be renamed — used by the output
        // verification below; identity rows legitimately produce none
        val expectedRenames = probe.mapNotNull { name ->
            view.getClass(fns, InternalName.unchecked(name))
                ?.names?.get(tns)?.value?.takeIf { it != name }
        }.toSet()

        // the mod-view classpath is identical across a batch's transforms
        // (sibling mod jars + the source-ns MC artifact) — its class→jar
        // index is immutable data, so it is cached per jar-set fingerprint
        // daemon-wide; the lazy tree only parses classpath classes that
        // hierarchy-resolution actually touches (unrelated libs — most of
        // the classpath — are never opened); propagation mutates ClassInfo,
        // so each remap gets a fresh tree over the shared index + this
        // input's own eager scan
        // scan aliases: a jar natively in the live namespace just joins
        // the classpath index; a foreign-namespace jar contributes its
        // scanned class info name-translated into fns — folded into the
        // batch graph below (the modsource-MC path: the official merged
        // jar's intermediary analysis data without a jar remap)
        val (sameNsJars, foreignAliases) = ctx.spec.scanAliases
            .partition { Namespace(it.sourceNs) == fns }
        val classpathJars = (ctx.classpath + sameNsJars.map { it.jar })
            .filter { it.exists() }
        val classpathKey = classpathJars
            .map { Triple(it, Files.size(it), Files.getLastModifiedTime(it).toMillis()) }
            .sortedBy { it.first }
        val scanAliasKey = foreignAliases.map {
            ScanAliasKey(
                Triple(it.jar, Files.size(it.jar), Files.getLastModifiedTime(it.jar).toMillis()),
                it.sourceNs,
                Triple(it.mappingsFile, Files.size(it.mappingsFile),
                    Files.getLastModifiedTime(it.mappingsFile).toMillis())
            )
        }
        // computeIfAbsent (not getOrPut) — the map's put must be atomic:
        // non-atomic get-then-put raced N first-access transforms into
        // N parallel index builds of the same 113-jar classpath
        val index = scanCache.computeIfAbsent(classpathKey) {
            slog.lifecycle(
                "indexing classpath (${classpathJars.size} jars) for lazy inheritance"
            )
            xyz.wagyourtail.unimined.api.util.Perf.span(
                "classpath.index", "jars" to classpathJars.size
            ) {
                LazyASMInheritanceTree.index(classpathJars.toSet())
            }
        }
        // a pass claims heap units by INPUT SIZE — a 200 KiB mod jar
        // and the 60 MiB MC artifact do not have the same marginal
        // footprint (input scan + output/extension buffers scale with
        // input; merged tree, member groups and the classpath graph are
        // all shared via the batch below, so the old multi-GB-per-pass
        // accounting is gone). The clamp keeps a huge input schedulable
        // on heaps where its tier exceeds the unit count.
        val permitCost = remapPermitCost(Files.size(input))
            .coerceAtMost(REMAP_PERMIT_UNITS)
        remapPermits.acquire(permitCost)
        try {
            // merged tree + propagated member groups depend only on
            // (mappings, classpath, namespaces, access model) — constant
            // across a mod-view batch, so they are shared via a
            // RemapBatch instead of being rebuilt (~500k rows) per jar.
            // SoftReference: under heap pressure GC may reclaim the
            // batch and the next jar pays one rebuild rather than the
            // whole batch OOMing the daemon.
            val batch = remapBatch(
                BatchKey(
                    System.identityHashCode(ctx.mappings), classpathKey,
                    scanAliasKey,
                    fns, tns, ctx.accessModel?.serialize()?.hashCode() ?: 0
                )
            ) {
                slog.lifecycle("preparing shared remap batch " +
                    "(${classpathJars.size}-jar classpath, " +
                    "${foreignAliases.size} scan aliases) for $fns -> $tns")
                // foreign-namespace classpath members enter the graph as
                // translated scans — the batch sees the same ClassInfos a
                // remapped jar would carry, without the jar remap
                val extraScans = foreignAliases.map { translatedScan(it, fns, slog) }
                // remap-analysis overlay: AW/AT/CT rules declared by the
                // batch ride the trees list as a synthetic
                // AccessElement-bearing layer, folded into the
                // inheritance scan before propagation. Built inside the
                // prepare thunk so cache hits skip the entries() walk.
                val overlay = ctx.accessModel
                    ?.entries(spec.sourceNamespace, referenceTree)
                    ?.takeUnless { it.isEmpty() }
                    ?.let { accessModelOverlay(it, fns, referenceTree) }
                runBlocking {
                    AsmRemapper.prepareBatch(
                        listOf(view) + listOfNotNull(overlay),
                        fns, tns, index = index,
                        extraScans = extraScans
                    )
                }
            }
            val inputScan = xyz.wagyourtail.unimined.api.util.Perf.span(
                "uml:remap.scan", "input" to input.fileName.toString()
            ) {
                ASMInheritanceTree.scan(setOf(input))
            }

            // inline progress — the callback fires per class from UML's
            // parallel workers; the gate turns it into a throttled
            // lifecycle line so the log shows movement instead of a
            // multi-second stall
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
                batch.remap(
                    input,
                    target,
                    inputScan,
                    // FIX — package-private members of classes whose package
                    // the mappings relocate get widened (tiny's
                    // checkPackageAccess+fixPackageAccess equivalent)
                    options = Options(
                        packageAccess = PackageAccess.FIX,
                        // 1.x `fixInners` — env-artifact pipelines inject
                        // obf && mc<1.8.2; unset (mod jars, dep views) is
                        // off, matching 1.x never running it there
                        fixInnerClasses = fixInnerClasses ?: false,
                        // decompiler/obf synthetic param names ($$0, $$1…)
                        // are valid identifiers — flag them via pattern so
                        // renameInvalidLocals replaces them
                        invalidLvNamePattern = Regex(".*\\$\\$.*"),
                        // JSR-305 annotation descriptors -> JetBrains.
                        // mod-artifact extensions (kotlin metadata, mixin
                        // refmaps, JMA refmaps) run as the follow-on
                        // `modArtifacts` step — engine-agnostic so tiny or
                        // remapped-view pipelines get identical treatment
                        extensions = BuiltinAsmExtensions.defaults() +
                            (if (jsr305) listOf(Jsr305AsmExtension.factory())
                             else emptyList()),
                        onProgress = { p, d, t -> phase = p; total = t; gate.tickTo(d.toLong()) }
                    )
                )
            }
        } finally {
            remapPermits.release(permitCost)
        }
        // output verification — expected renames that never appear mean
        // the dst layer produced an identity copy (the forge-1.2.5
        // official->mcp bug: an exists-but-empty layer "remapped" nothing)
        if (expectedRenames.isNotEmpty()) {
            val produced = xyz.wagyourtail.unimined.api.util.Perf.span(
                "uml:remap.verify", "input" to input.fileName.toString()
            ) {
                java.util.zip.ZipFile(target.toFile()).use { z ->
                    z.entries().asSequence()
                        .filter { !it.isDirectory && it.name.endsWith(".class") }
                        .map { it.name.removeSuffix(".class") }
                        .toHashSet()
                }
            }
            if (produced.none { it in expectedRenames }) {
                throw GradleException(
                    "uml:remap ${input.fileName}: none of the " +
                        "${expectedRenames.size} expected class renames " +
                        "appear in the output — the " +
                        "'${spec.targetNamespace}' layer produced an " +
                        "identity remap"
                )
            }
        }
        // publish the artifact's new namespace so downstream steps
        // validate against the post-remap truth
        ctx.state.currentNamespace = spec.targetNamespace
        return target
    }

    internal companion object {
        /** Daemon-lifetime classpath-index cache — keyed on the ordered
         *  jar-path set (with size+mtime so same-path rebuilds still
         *  re-index), so all transforms sharing a view classpath share
         *  one listing. */
        val scanCache = java.util.concurrent.ConcurrentHashMap<
                List<Triple<Path, Long, Long>>, Map<InternalName, Path>>()

        /** Identity of one remap batch — same mappings payload, same
         *  classpath fingerprint, same translated-scan aliases, same
         *  namespaces and access model means the merged tree +
         *  propagated groups are identical and can be shared. The
         *  mappings instance is daemon-shared via the transform's
         *  data cache, so identity hash is stable within a build. */
        internal data class BatchKey(
            val mappingsId: Int,
            val classpathKey: List<Triple<Path, Long, Long>>,
            val scanAliasKey: List<ScanAliasKey>,
            val fns: Namespace,
            val tns: Namespace,
            val accessModelHash: Int,
        )

        /** Fingerprint of one translated-scan alias — the jar bytes,
         *  the namespace it scans from and the bundle it translates
         *  through (path+size+mtime so a rebuilt file re-keys). */
        internal data class ScanAliasKey(
            val jar: Triple<Path, Long, Long>,
            val sourceNs: String,
            val mappingsFile: Triple<Path, Long, Long>,
        )

        /** Daemon-lifetime cache of name-translated alias scans —
         *  [ASMInheritanceTree.ScanData] is immutable and shareable, so
         *  one official->intermediary MC scan serves every batch in the
         *  env (modImplementation + modRuntime views). SoftReference:
         *  same GC-pressure escape hatch as [batchCache]. */
        internal val aliasScanCache = java.util.concurrent.ConcurrentHashMap<
                ScanAliasKey, java.lang.ref.SoftReference<ASMInheritanceTree.ScanData>>()

        /** Scan [alias]'s jar and rename the result
         *  `alias.sourceNs -> fns` through its mapping bundle — the
         *  class-info view a modsource-namespace remapped jar would
         *  have carried, produced without a bytecode pass. Cached on
         *  [aliasScanCache] so repeat batches across views pay the
         *  scan once per env. */
        internal fun translatedScan(
            alias: xyz.wagyourtail.unimined.api.spec.ClasspathScanAlias,
            fns: Namespace,
            log: xyz.wagyourtail.commons.core.logger.Logger
        ): ASMInheritanceTree.ScanData {
            val key = ScanAliasKey(
                Triple(alias.jar, Files.size(alias.jar),
                    Files.getLastModifiedTime(alias.jar).toMillis()),
                alias.sourceNs,
                Triple(alias.mappingsFile, Files.size(alias.mappingsFile),
                    Files.getLastModifiedTime(alias.mappingsFile).toMillis())
            )
            var scan = aliasScanCache[key]?.get()
            if (scan == null) {
                synchronized(aliasScanCache) {
                    scan = aliasScanCache[key]?.get()
                    if (scan == null) {
                        log.lifecycle(
                            "scanning ${alias.jar.fileName} " +
                            "(${alias.sourceNs} -> ${fns.name} names) " +
                            "for lazy inheritance"
                        )
                        val t0 = System.nanoTime()
                        val data = xyz.wagyourtail.unimined.api.mapping
                            .SerializedMappingData.readEither(alias.mappingsFile)
                        val tree = data.view<MappingTree>()
                            ?: throw GradleException(
                                "scan-alias mappings $alias.mappingsFile " +
                                "expose no UML MappingTree view"
                            )
                        scan = xyz.wagyourtail.unimined.api.util.Perf.span(
                            "classpath.scanAlias", "jar" to alias.jar.fileName.toString()
                        ) {
                            ASMInheritanceTree.scan(setOf(alias.jar)).renamed(
                                tree.remapper(Namespace(alias.sourceNs), fns)
                            )
                        }
                        log.lifecycle(
                            "scan-alias ${alias.jar.fileName}: " +
                            "${scan!!.classes.size} classes in " +
                            "${(System.nanoTime() - t0) / 1_000_000}ms"
                        )
                        aliasScanCache[key] = java.lang.ref.SoftReference(scan)
                    }
                }
            }
            return scan!!
        }

        /** Daemon-lifetime shared remap batches — a soft reference keeps
         *  a GC-pressure escape hatch (rebuild on demand rather than
         *  pinning multi-GB merged trees). Internal so `modArtifacts`
         *  (same batch, same keying) shares the remap step's prepared
         *  state instead of rebuilding it. */
        internal val batchCache = java.util.concurrent.ConcurrentHashMap<
                BatchKey, java.lang.ref.SoftReference<RemapBatch>>()

        /** Double-checked soft-ref lookup over [batchCache]; [prepare]
         *  runs at most once per key even under concurrent first access.
         *  Keep [prepare] self-contained — anything expensive it needs
         *  (e.g. the access-model overlay) should be built inside it so
         *  cache hits skip the work entirely. */
        internal fun remapBatch(key: BatchKey, prepare: () -> RemapBatch): RemapBatch {
            var batch = batchCache[key]?.get()
            if (batch == null) {
                synchronized(batchCache) {
                    batch = batchCache[key]?.get()
                    if (batch == null) {
                        batch = prepare()
                        batchCache[key] = java.lang.ref.SoftReference(batch)
                    }
                }
            }
            return batch!!
        }

        /**
         * Daemon-wide bound on concurrent ASM remap passes, in ~192 MiB
         * heap units. With the batch-shared merged tree/groups/graph,
         * a pass's marginal footprint is its input scan + buffered
         * output — roughly proportional to input size — so the unit
         * count is min(cores×4, heap÷192 MiB) with a floor of 12, and
         * each pass claims units by input size (see
         * [remapPermitCost]): ~8 small-mod passes per 8 cores as
         * before, but an MC-scale input claims ~6× a mod jar's units
         * instead of running alongside a full wave. The batch's own
         * multi-GB shared state is not permit-bounded; heaps too small
         * to hold it fail regardless.
         */
        val REMAP_PERMIT_UNITS = minOf(
            Runtime.getRuntime().availableProcessors() * 4,
            (Runtime.getRuntime().maxMemory() / (192L * 1024 * 1024)).toInt()
        ).coerceAtLeast(12)

        val remapPermits = java.util.concurrent.Semaphore(REMAP_PERMIT_UNITS)

        /** heap units a remap pass claims for an input of [inputBytes] */
        fun remapPermitCost(inputBytes: Long): Int = when {
            inputBytes < 4L * 1024 * 1024 -> 4    // ≤~768 MiB — a mod jar
            inputBytes < 32L * 1024 * 1024 -> 8   // ~1.5 GiB
            else -> 24                            // MC-scale: ~4 GiB
        }
    }

}
