package xyz.wagyourtail.unimined.minecraft.transform

import org.gradle.api.artifacts.transform.*
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.FileSystemLocation
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.logging.Logging
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import xyz.wagyourtail.unimined.api.spec.Side
import xyz.wagyourtail.unimined.api.util.Perf
import xyz.wagyourtail.unimined.minecraft.spi.ProductArtifactProducerRegistry
import xyz.wagyourtail.unimined.minecraft.internal.RuntimeProductSpec
import xyz.wagyourtail.unimined.minecraft.internal.RuntimeVersionSpec
import xyz.wagyourtail.unimined.minecraft.spi.ArtifactKey
import xyz.wagyourtail.unimined.minecraft.spi.ArtifactProductionRequest
import java.nio.file.Files
import xyz.wagyourtail.commonskt.jvm.copyAtomicTo
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration

/**
 * Converts a marker-stub jar into the environment's raw Minecraft artifact
 * (client/server/merged jar in the provider's source namespace) at
 * resolution time — lazily, cacheably, and during IDE sync.
 *
 * Producer impls are rediscovered via `ServiceLoader` inside the action so
 * nothing Gradle-model lives in serialized transform state. The spec is
 * deliberately loader-free: this transform produces the *vanilla-side*
 * artifact; loader transformation is the dev-pipeline step's job.
 */
@CacheableTransform
abstract class ProductArtifactTransform : TransformAction<ProductArtifactTransform.Params> {

    interface Params : TransformParameters {

        @get:Input
        val environmentId: Property<String>

        @get:Input
        val version: Property<String>

        @get:Input
        val side: Property<String>

        @get:Input
        val artifactKey: Property<String>

        /** Output filename stem (`MinecraftEnvironment
         *  .artifactBaseName`) — `forge-<fullVersion>` on forge-family
         *  environments so FML's `matchFileName` finds the jar. */
        @get:Input
        val artifactBaseName: Property<String>

        /** Producer selection override (`minecraft.provider`). */
        @get:Optional
        @get:Input
        val producerName: Property<String>

        /** Declared loader id+version — producers keyed on the loader
         *  product (total-conversion dists) see it on the spec. */
        @get:Optional
        @get:Input
        val loaderName: Property<String>

        @get:Optional
        @get:Input
        val loaderVersion: Property<String>

        /** Family-scoped product id (`minecraft:native` default) —
         *  producers key their product tables on it. */
        @get:Optional
        @get:Input
        val product: Property<String>

        /** User-declared dist source spec (`legacydist {}`) —
         *  Serializable so producers that materialize declared
         *  artifacts (legacydist, devbundle, total-conversion dists
         *  without an index) see the source *and* its
         *  `namespace`/`format` markers on the rebuilt spec — the
         *  format tag is what keeps `devbundle`-marked declarations
         *  negotiated to the devbundle producer. */
        @get:Optional
        @get:Input
        val declaredSource: Property<xyz.wagyourtail.unimined.api.spec.DeclaredSourceSpec>

        /** Selected producer's output-format version — bumps the cache
         *  key whenever the production pipeline's output changes for
         *  identical inputs. */
        @get:Input
        val artifactFormatVersion: Property<Int>

        /** Provider cache root — treated as a content-verified side cache,
         *  not a fingerprinted input. */
        @get:Internal
        val cacheDir: Property<String>

        @get:Input
        val offline: Property<Boolean>

        @get:Input
        val refreshUniminedDownloads: Property<Boolean>

        @get:Input
        val rebuildUniminedArtifacts: Property<Boolean>

        @get:Optional
        @get:Input
        val metadataCacheTtl: Property<String>

        /** Resolved `minecraftLibraries` jars the merge strip checks
         *  server entries against (`@Classpath` — content
         *  fingerprinted so library-set changes re-run the merge). */
        @get:InputFiles
        @get:Classpath
        val libraryJars: ConfigurableFileCollection

        /** Merge keep-override entry globs (`MinecraftEnvironment
         *  .mergeIncludeGlobs`). */
        @get:Input
        val mergeIncludeGlobs: ListProperty<String>

        /** Extra server-side strip globs (`MinecraftEnvironment
         *  .mergeExcludeGlobs`). */
        @get:Input
        val mergeExcludeGlobs: ListProperty<String>

        /** Master switch for the server-bundled-library strip. */
        @get:Input
        val stripBundledServerLibraries: Property<Boolean>

        /** Side-marker annotations stamped on single-side merged
         *  content (`MinecraftEnvironment.sideMarkers` — Serializable
         *  value objects, fingerprinted per element). */
        @get:Input
        val sideMarkers: ListProperty<
                xyz.wagyourtail.unimined.minecraft.merge.JarMerger
                        .SideMarker>

        /** Pre-merge side-jar patch passes
         *  (`MinecraftEnvironment.sideJarPatches`) — Serializable
         *  specs resolved against `SideJarPatchProcessor`
         *  ServiceLoader impls at execution. */
        @get:Input
        val sideJarPatches: ListProperty<
                xyz.wagyourtail.unimined.minecraft.spi
                        .SideJarPatchSpec>

        /** Shared namespace both sides are remapped into before
         *  merging — set only on split-side merged environments
         *  (`official` is a union of disjoint client/server obf
         *  namespaces). */
        @get:Optional
        @get:Input
        val mergeNamespace: Property<String>

        /** Per-side official namespaces each side is remapped *from*
         *  before merging — `[client, server]` order (split-era obf
         *  namespaces are disjoint; same-named classes differ). */
        @get:Optional
        @get:Input
        val mergeFromNamespaces: ListProperty<String>

        /** z64-serialized mapping payload for the pre-merge remaps
         *  (same encoding `RemapDependencyTransform` decodes). */
        @get:Optional
        @get:Input
        val mergeMappings: Property<String>

        /** File variant of [mergeMappings] — same payload, content-
         *  addressed; preferred so the tree stays out of param values. */
        @get:Optional
        @get:InputFile
        @get:PathSensitive(PathSensitivity.NONE)
        val mergeMappingsFile: RegularFileProperty

    }

    @get:InputArtifact
    @get:Classpath
    abstract val inputArtifact: Provider<FileSystemLocation>

    override fun transform(outputs: TransformOutputs) {
        val t0 = System.nanoTime()
        val p = parameters
        val spec = RuntimeProductSpec(
            environmentId = p.environmentId.get(),
            sourceSetName = p.environmentId.get(),
            version = RuntimeVersionSpec(p.version.get()),
            side = Side.valueOf(p.side.get()),
            loader = p.loaderName.orNull?.let {
                xyz.wagyourtail.unimined.minecraft.internal.RuntimeLoaderSpec(
                    it, p.loaderVersion.orNull ?: ""
                )
            },
            sideMarkers = p.sideMarkers.get(),
            product = p.product.orNull
                ?: xyz.wagyourtail.unimined.api.spec.ProductSpec.PRODUCT_NATIVE,
            // the full declared spec rides the transform so producers
            // can negotiate on `format` markers the same way providers
            // do (devbundle's tag claims ahead of legacydist's floor)
            declaredSource = p.declaredSource.orNull
        )
        val producer = ProductArtifactProducerRegistry()
            .select(spec, p.producerName.orNull)
        val request = ArtifactProductionRequest(
            cacheDir = Path.of(p.cacheDir.get()),
            artifactKey = ArtifactKey.valueOf(p.artifactKey.get()),
            offline = p.offline.get(),
            refreshUniminedDownloads = p.refreshUniminedDownloads.get(),
            rebuildUniminedArtifacts = p.rebuildUniminedArtifacts.get(),
            metadataCacheTtl = p.metadataCacheTtl.orNull?.let(Duration::parse),
            libraryJars = p.libraryJars.files.map { it.toPath() },
            mergeIncludeGlobs = p.mergeIncludeGlobs.get(),
            mergeExcludeGlobs = p.mergeExcludeGlobs.get(),
            stripBundledServerLibraries = p.stripBundledServerLibraries.get(),
            sideMarkers = p.sideMarkers.get(),
            // side-jar patching runs before the split-era namespace
            // bridge — patches index each side's own obf names, which
            // the bridge rewrites
            preMerge = listOfNotNull(
                sidePatchHook(p), preMergeHook(p)
            ).takeIf { it.isNotEmpty() }?.let { hooks ->
                { c: Path, s: Path ->
                    hooks.fold(c to s) { acc, h -> h(acc.first, acc.second) }
                }
            },
            logger = xyz.wagyourtail.unimined.api.util.uniminedLogger(Logging.getLogger(ProductArtifactTransform::class.java))
        )
        val produced = producer.produce(spec, request)
        val output = outputs.file(
            "${p.artifactBaseName.get()}-" +
                    p.artifactKey.get().lowercase() + ".jar"
        ).toPath()
        produced.copyAtomicTo(output)
        Perf.emit("produce.transform", "env" to p.environmentId.get(),
            "key" to p.artifactKey.get(), "version" to p.version.get(),
            "ms" to (System.nanoTime() - t0) / 1_000_000)
    }

    /**
     * Build the pre-merge hook for loader-contributed side-jar patch
     * passes ([Params.sideJarPatches]) — each spec's
     * `SideJarPatchProcessor` runs on both clean side jars, which the
     * merge then consumes (1.x forge binpatch order: patch sides,
     * then merge so marker stamping applies to patched content).
     * `null` when no specs were contributed.
     */
    private fun sidePatchHook(p: Params): ((Path, Path) -> Pair<Path, Path>)? {
        val specs = p.sideJarPatches.get()
        if (specs.isEmpty()) return null
        val processors = java.util.ServiceLoader.load(
            xyz.wagyourtail.unimined.minecraft.spi
                .SideJarPatchProcessor::class.java,
            xyz.wagyourtail.unimined.minecraft.spi
                .SideJarPatchProcessor::class.java.classLoader
        ).associateBy { it.type }
        val workDir = Files.createTempDirectory("unimined-side-patch")
        return { client, server ->
            var c = client
            var s = server
            for (spec in specs) {
                val proc = processors[spec.type]
                    ?: error(
                        "no SideJarPatchProcessor named '${spec.type}' " +
                                "on the classpath (specs: " +
                                processors.keys.joinToString() + ")"
                    )
                c = proc.patch("client", c, spec.args, workDir)
                s = proc.patch("server", s, spec.args, workDir)
            }
            c to s
        }
    }

    /**
     * Build the pre-merge hook for split-side merged environments —
     * both side jars are remapped from the union `official` namespace
     * into the shared namespace, so the member-level merge sees the
     * same logical classes. `null` when the env didn't declare a
     * merge namespace (post-1.3 merges stay official-space).
     */
    private fun preMergeHook(p: Params): ((Path, Path) -> Pair<Path, Path>)? {
        val mergeNs = p.mergeNamespace.orNull ?: return null
        val mappings = p.mergeMappingsFile.orNull?.let {
            xyz.wagyourtail.unimined.api.mapping
                .SerializedMappingData.readEither(it.asFile.toPath())
        } ?: run {
            val z64 = p.mergeMappings.orNull
                ?: error("mergeNamespace set without mergeMappings(File)")
            require(z64.startsWith("z64:")) {
                "mergeMappings must be a z64 SerializedMappingData payload"
            }
            val tmp = Files.createTempFile("unimined-merge-mappings", ".zip")
            try {
                Files.write(
                    tmp,
                    java.util.Base64.getDecoder().decode(z64.removePrefix("z64:"))
                )
                xyz.wagyourtail.unimined.api.mapping
                    .SerializedMappingData.readEither(tmp)
            } finally {
                Files.deleteIfExists(tmp)
            }
        }
        val remapper = java.util.ServiceLoader.load(
            xyz.wagyourtail.unimined.api.spi.JarRemapper::class.java,
            xyz.wagyourtail.unimined.api.spi.JarRemapper::class.java.classLoader
        ).firstOrNull()
            ?: error("no JarRemapper SPI implementation on the classpath")
        val fns = p.mergeFromNamespaces.get()
        require(fns.size == 2) {
            "mergeFromNamespaces must hold [client, server] columns"
        }
        // env-qualified columns split to (base ns, env) — the
        // env-selected view `base ∪ base:env` resolves sided rows
        // first, then joined rows in `base`
        val (clientNs, clientEnv) = xyz.wagyourtail.unimined.mapping
            .Namespace(fns[0]).splitEnv()
        val (serverNs, serverEnv) = xyz.wagyourtail.unimined.mapping
            .Namespace(fns[1]).splitEnv()
        return { client, server ->
            val c = Files.createTempFile("unimined-merge-client", ".jar")
            val s = Files.createTempFile("unimined-merge-server", ".jar")
            remapper.remap(client, c, mappings, clientNs.name, mergeNs, clientEnv)
            remapper.remap(server, s, mappings, serverNs.name, mergeNs, serverEnv)
            c to s
        }
    }

}
