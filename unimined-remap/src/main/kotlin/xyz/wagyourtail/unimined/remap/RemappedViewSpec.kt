package xyz.wagyourtail.unimined.remap

import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
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
     * Explicit `artifactType` token for the view's transform request,
     * bypassing the namespace-derived default. Internal host wiring sets
     * this when the target namespace is only known lazily — the token is
     * just a routing key and need not embed it.
     */
    @get:Internal
    abstract val artifactTypeToken: Property<String>

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

}
