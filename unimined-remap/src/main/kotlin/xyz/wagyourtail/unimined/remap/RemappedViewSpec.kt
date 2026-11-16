package xyz.wagyourtail.unimined.remap

import groovy.lang.Closure
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import xyz.wagyourtail.unimined.api.util.dependencyNotationPredicate
import xyz.wagyourtail.unimined.remap.spi.StepSpec

/**
 * Spec for a remapped dependency view — see
 * [RemapExtension.remappedView].
 */
abstract class RemappedViewSpec {

    /** Namespace the wrapped configuration's artifacts are in. */
    @get:Input
    abstract val sourceNamespace: Property<String>

    /** Namespace the view resolves them to. */
    @get:Input
    abstract val targetNamespace: Property<String>

    /**
     * Optional suffix appended to the requested `unimined.namespace`
     * attribute (`named-envA`), so two views targeting the same namespace
     * with different pipelines/mappings don't collide on one transform
     * registration.
     */
    @get:Input
    abstract val discriminator: Property<String>

    /**
     * Serialized [xyz.wagyourtail.unimined.api.mapping.MappingBundle] for
     * this view's remap. Required when any pipeline step declares
     * `needsMappingTree`.
     */
    @get:Optional
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val mappingFile: RegularFileProperty

    /** Classpath entries for steps that declare `needsClasspath`. */
    @get:InputFiles
    @get:Classpath
    abstract val classpath: ConfigurableFileCollection

    /**
     * The pipeline this view runs — defaults to the project's
     * [RemapSurface.JAR] pipeline; set it explicitly for a per-view
     * override.
     */
    @get:Nested
    abstract val stepSpecs: ListProperty<StepSpec>

    /**
     * Serialized bundle content, computed lazily by host wiring —
     * preferred over [mappingFile] for environment-managed views since a
     * transform parameter cannot carry a task dependency. Evaluating the
     * provider materializes mappings, so presence must be checked via
     * [hasLazyMappings] rather than `isPresent`.
     */
    @get:Internal
    abstract val mappingBundleJson: Property<String>

    /**
     * Serialized [xyz.wagyourtail.unimined.api.access.AccessModel] —
     * the merged AW/AT/CT rules of this view's remap batch, fed to
     * `needsAccessModel` steps as the remap-analysis overlay. Host wiring
     * sets it lazily from environment contributions; absent means no
     * overlay.
     */
    @get:Internal
    abstract val accessModel: Property<String>

    /** Whether [mappingBundleJson] was set — checked without evaluating it. */
    @get:Internal
    var hasLazyMappings: Boolean = false

    /**
     * Whether [mappingFile] was bound to a lazily-materializing provider
     * (environment mapping bundles) — checked without evaluating it,
     * like [hasLazyMappings]. A plain user-set file stays `false` and is
     * still detectable via `isPresent`.
     */
    @get:Internal
    var hasLazyMappingFile: Boolean = false

    /**
     * Explicit `artifactType` token for the view's transform request,
     * bypassing the namespace-derived default. Internal host wiring sets
     * this when the target namespace is only known lazily — the token is
     * just a routing key and need not embed it.
     */
    @get:Internal
    abstract val artifactTypeToken: Property<String>

    /**
     * Classpath jars whose class files live in a foreign namespace —
     * the remap engine folds their name-translated inheritance scan
     * into the batch graph instead of remapping them as artifacts
     * (see [xyz.wagyourtail.unimined.api.spec.RemapSpec.scanAliases]).
     * Presence must go through [hasScanAlias] — host wiring binds the
     * fields lazily, so `isPresent`/`files` checks could materialize
     * environment mappings at registration.
     */
    @get:Internal
    abstract val scanAliasJars: ConfigurableFileCollection

    /** The namespace [scanAliasJars]' classes are natively in. */
    @get:Internal
    abstract val scanAliasFromNs: Property<String>

    /** Mapping bundle carrying `scanAliasFromNs` -> `sourceNamespace`. */
    @get:Internal
    abstract val scanAliasMappingFile: RegularFileProperty

    /** Whether the scan-alias fields were bound — checked without
     *  evaluating them, like [hasLazyMappingFile]. */
    @get:Internal
    var hasScanAlias: Boolean = false

    /**
     * Mod indicators for [onlyRemapMarkedJars] — prefixed matchers
     * (`file:`, `suffix:`, `manifest:`, `refs:`) scanned per artifact;
     * see [xyz.wagyourtail.unimined.remap.internal.ModMarkerScan] for
     * semantics and the default set. Replaceable for loaders with
     * different markers.
     */
    @get:Input
    abstract val modMarkers: ListProperty<String>

    /**
     * When true (default), artifacts without a mod marker pass through
     * unmapped — plain Java libraries on a `mod*` view don't reference
     * mapped classes, so the full pipeline is wasted on them. Set false
     * to remap every artifact.
     */
    @get:Input
    abstract val onlyRemapMarkedJars: Property<Boolean>

    /**
     * Per-dependency force-remap filters — the user-side escape of
     * `Fabric-Loom-Remap:true` for deps too plain to self-describe
     * (e.g. obf-named libraries the `refs:` scan can't see). Each
     * filter is a `(Dependency) -> Boolean` evaluated against the
     * source configuration's declared dependencies at resolution
     * time; matching deps' own artifacts (never their transitives)
     * remap unconditionally. Resolved lazily — filters registered
     * after the view is created still apply.
     */
    @get:Internal
    val forceRemapFilters: MutableList<(org.gradle.api.artifacts.Dependency) -> Boolean> =
        mutableListOf()

    /**
     * Resolved artifacts of [forceRemapFilters]-matching dependencies —
     * host wiring fills this collection; the transform reads it as the
     * forced file set. Not user-facing.
     */
    @get:Internal
    abstract val forceRemapJars: ConfigurableFileCollection

    /**
     * Label identifying this spec in error messages — host wiring sets
     * it to `remapped view '<configuration name>'` so a rejected
     * [forceRemap] coordinate points back at the view that refused it.
     * The generic default covers specs built outside view wiring.
     */
    @get:Internal
    var errorContext: String = "a remapped view"

    /** Force-remap deps matching [filter] — see [forceRemapFilters]. */
    fun forceRemap(filter: (org.gradle.api.artifacts.Dependency) -> Boolean) {
        forceRemapFilters += filter
    }

    /** Groovy DSL overload — `forceRemap { it.group == "com.example" }`. */
    fun forceRemap(filter: Closure<Boolean>) =
        forceRemap { dep -> filter.call(dep) }

    /** Force-remap deps by `group:name[:version]` coordinate —
     *  malformed notation fails with an actionable error naming this
     *  view (see [dependencyNotationPredicate]). */
    fun forceRemap(notation: String) =
        forceRemap(
            dependencyNotationPredicate(
                notation,
                "forceRemap on $errorContext"
            )
        )

}
