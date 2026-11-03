package xyz.wagyourtail.unimined.remap

import groovy.lang.Closure
import groovy.lang.DelegatesTo
import org.gradle.api.Action
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import xyz.wagyourtail.unimined.remap.spi.StepSpec
import javax.inject.Inject

/**
 * DSL for a set of remapped dependency views that accept different input
 * namespaces but resolve to one target namespace.
 *
 * Declare one dependency bucket per input namespace — dependencies are
 * deliberately not classified from coordinates. For example:
 *
 * ```kotlin
 * remap.remappedViews("mods") {
 *     targetNamespace.set("named")
 *     namespace("official", "obfMods") {
 *         mappingFile.set(officialToNamed)
 *     }
 *     namespace("intermediary", "intermediaryMods") {
 *         mappingFile.set(intermediaryToNamed)
 *     }
 * }
 * ```
 */
abstract class RemappedViewsSpec @Inject constructor(
    private val objects: ObjectFactory
) {

    internal lateinit var baseName: String
    internal val namespaces = mutableListOf<RemappedNamespaceSpec>()

    /** Common namespace all generated views resolve to. */
    @get:Input
    abstract val targetNamespace: Property<String>

    /** Optional shared suffix for the generated artifact types. */
    @get:Input
    abstract val discriminator: Property<String>

    /** Classpath entries shared by every generated view. */
    @get:InputFiles
    @get:Classpath
    abstract val classpath: ConfigurableFileCollection

    /**
     * Serialized
     * [xyz.wagyourtail.unimined.api.access.AccessModel] shared by every
     * generated view — sections are keyed by the namespace their names
     * are written in, so one model can serve mixed-namespace batches.
     */
    @get:Internal
    abstract val accessModel: Property<String>

    /** Pipeline shared by every generated view; defaults to this environment's JAR pipeline. */
    @get:Nested
    abstract val stepSpecs: ListProperty<StepSpec>

    /**
     * Mod indicators shared by every generated view — see
     * [RemappedViewSpec.modMarkers]. Convention carries
     * `ModMarkerScan.DEFAULT_MARKERS` plus environment contributions.
     */
    @get:Input
    abstract val modMarkers: ListProperty<String>

    /** See [RemappedViewSpec.onlyRemapMarkedJars]. */
    @get:Input
    abstract val onlyRemapMarkedJars: Property<Boolean>

    /** Add a namespace using the default `<baseName><Namespace>` dependency bucket. */
    fun namespace(sourceNamespace: String): RemappedNamespaceSpec =
        namespace(sourceNamespace, defaultSourceConfiguration(sourceNamespace), Action {})

    /** Add a namespace and configure its per-namespace mapping bundle. */
    fun namespace(
        sourceNamespace: String,
        action: Action<RemappedNamespaceSpec>
    ): RemappedNamespaceSpec =
        namespace(sourceNamespace, defaultSourceConfiguration(sourceNamespace), action)

    /** Groovy DSL overload using the default dependency bucket name. */
    fun namespace(
        sourceNamespace: String,
        @DelegatesTo(value = RemappedNamespaceSpec::class, strategy = Closure.DELEGATE_FIRST)
        action: Closure<*>
    ): RemappedNamespaceSpec = namespace(
        sourceNamespace,
        defaultSourceConfiguration(sourceNamespace),
        action
    )

    /** Add a namespace backed by an explicitly named dependency bucket. */
    fun namespace(
        sourceNamespace: String,
        sourceConfiguration: String
    ): RemappedNamespaceSpec =
        namespace(sourceNamespace, sourceConfiguration, Action {})

    /** Add a namespace backed by an explicitly named dependency bucket. */
    fun namespace(
        sourceNamespace: String,
        sourceConfiguration: String,
        action: Action<RemappedNamespaceSpec>
    ): RemappedNamespaceSpec {
        if (namespaces.any { it.sourceNamespace == sourceNamespace }) {
            throw GradleException(
                "Duplicate source namespace '$sourceNamespace' in remapped view set '$baseName'"
            )
        }
        val namespace = objects.newInstance(
            RemappedNamespaceSpec::class.java,
            sourceNamespace,
            sourceConfiguration
        )
        action.execute(namespace)
        namespaces += namespace
        return namespace
    }

    /** Groovy DSL overload for an explicitly named dependency bucket. */
    fun namespace(
        sourceNamespace: String,
        sourceConfiguration: String,
        @DelegatesTo(value = RemappedNamespaceSpec::class, strategy = Closure.DELEGATE_FIRST)
        action: Closure<*>
    ): RemappedNamespaceSpec = namespace(
        sourceNamespace,
        sourceConfiguration,
        Action { namespace ->
            action.delegate = namespace
            action.resolveStrategy = Closure.DELEGATE_FIRST
            action.call()
        }
    )

    private fun defaultSourceConfiguration(sourceNamespace: String): String =
        baseName + sourceNamespace.replaceFirstChar(Char::uppercase)

}
