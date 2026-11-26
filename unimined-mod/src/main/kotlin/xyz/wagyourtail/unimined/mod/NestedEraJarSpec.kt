package xyz.wagyourtail.unimined.mod

import groovy.lang.Closure
import groovy.lang.DelegatesTo
import org.gradle.api.Action
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import xyz.wagyourtail.unimined.api.util.delegatingTo
import javax.inject.Inject

/**
 * Configuration for the single nested jar produced by an era merge. Fabric-
 * family era content is merged into this jar; the jar itself is written at
 * [path] inside the outer artifact.
 */
abstract class NestedEraJarSpec @Inject constructor(
    private val objects: ObjectFactory
) {

    /** Outer-jar path of the generated nested jar. Defaults to
     *  `META-INF/jars/<project-name>-nested.jar` when unset. */
    abstract val path: Property<String>

    /** Generated shell `fabric.mod.json` id. Defaults to `<parent>-inner`. */
    abstract val id: Property<String>

    /** Parent mod id written as ModMenu's `custom.modmenu.parent.id`. */
    abstract val parent: Property<String>

    /** Generated shell descriptor version; defaults to `mod.version`. */
    abstract val version: Property<String>

    /** Whether to emit the minimal shell `fabric.mod.json`; default `true`. */
    abstract val emitDescriptor: Property<Boolean>

    /** Paths written into the shell descriptor's `jars[]` array, in order. */
    abstract val nestedEntries: ListProperty<String>

    internal val physicalEntries = linkedMapOf<String, ConfigurableFileCollection>()

    internal val manifestSpec: EraMergeManifestSpec =
        objects.newInstance(EraMergeManifestSpec::class.java)

    init {
        emitDescriptor.convention(true)
    }

    /** Add [path] to the shell descriptor's `jars[]` without embedding a file. */
    fun nestedEntry(path: String) {
        nestedEntries.add(path)
    }

    /**
     * Embed [source] at [path] inside the nested jar and add [path] to the
     * shell descriptor's `jars[]`. [source] uses normal Gradle `files(...)`
     * semantics, so files, configurations, and task-output providers all work.
     */
    fun nestedJar(path: String, source: Any) {
        nestedEntry(path)
        physicalEntries.getOrPut(path) { objects.fileCollection() }.from(source)
    }

    /** Configure manifest attributes for the nested jar. */
    fun manifest(action: Action<EraMergeManifestSpec>) {
        action.execute(manifestSpec)
    }

    /** Groovy DSL overload for [manifest]. */
    fun manifest(
        @DelegatesTo(value = EraMergeManifestSpec::class, strategy = Closure.DELEGATE_FIRST)
        action: Closure<*>
    ) {
        action.delegatingTo(manifestSpec)
    }

}
