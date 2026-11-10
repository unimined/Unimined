package xyz.wagyourtail.unimined.loadermerge

import org.gradle.api.Action
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import java.io.File

/**
 * `loaderMerge { }` DSL — declares which per-loader production jars feed
 * the merge.
 *
 * With `xyz.wagyourtail.unimined` applied, every bound environment's
 * production jar is discovered automatically (non-aux envs, keyed by
 * loader name); `from(...)` entries add to or override that set on the
 * same key. When `io.github.pacifistmc.forgix` is also applied, its
 * `forgix { }` `mergeConfigurations` feed in as well — explicit
 * `from(...)` entries win over both.
 *
 * ```kotlin
 * loaderMerge {
 *     from("forge" to forgeJarTask, "fabric" to fabricJarTask)
 * }
 * ```
 */
abstract class LoaderMergeExtension {

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
