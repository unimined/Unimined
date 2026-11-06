package xyz.wagyourtail.unimined.loadermerge

import org.gradle.api.Action
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import java.io.File

/**
 * `loaderMerge { }` DSL — declares which per-loader production jars feed
 * the merge and which engine performs it.
 *
 * ```kotlin
 * loaderMerge {
 *     engine("forgix")
 *     from("forge" to forgeJarTask, "fabric" to fabricJarTask)
 * }
 * ```
 */
abstract class LoaderMergeExtension {

    /** Engine name — resolved against registered [ModLoaderMerger]s. */
    abstract val engine: org.gradle.api.provider.Property<String>

    /**
     * Named loader-jar inputs. Keys are loader names; values are files or
     * providers of files (task outputs work — dependencies are inferred).
     */
    fun from(vararg inputs: Pair<String, Any>) {
        for ((name, source) in inputs) input(name, source)
    }

    /** Single named input — `from("forge", forgeJarTask)`. */
    fun input(name: String, source: Any) {
        when (source) {
            is File, is Provider<*>, is RegularFile ->
                inputsInternal[name] = source

            else -> error(
                "unsupported loaderMerge input for '$name': " +
                        "${source::class.java.name} (want File or Provider)"
            )
        }
    }

    // raw sources — routed through `project.files(...)` at task
    // configuration so task-output providers keep their producer
    // dependency (evaluating to File here would strip it)
    internal val inputsInternal = linkedMapOf<String, Any>()

    /** Groovy-friendly vararg overload. */
    fun from(action: Action<InputsSpec>) {
        action.execute(object : InputsSpec {
            override fun input(name: String, source: Any) = this@LoaderMergeExtension.input(name, source)
        })
    }

    interface InputsSpec {
        fun input(name: String, source: Any)
    }

}
