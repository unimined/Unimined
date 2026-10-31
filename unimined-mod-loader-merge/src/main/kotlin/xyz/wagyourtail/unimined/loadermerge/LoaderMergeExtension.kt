package xyz.wagyourtail.unimined.loadermerge

import org.gradle.api.Action
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
            is File -> inputsInternal[name] = { listOf(source) }
            is Provider<*> -> inputsInternal[name] = {
                listOfNotNull(source.orNull).map { asFile(it) }
            }

            else -> error(
                "unsupported loaderMerge input for '$name': " +
                        "${source::class.java.name} (want File or Provider)"
            )
        }
    }

    internal val inputsInternal = linkedMapOf<String, () -> List<File>>()

    private fun asFile(value: Any?): File = when (value) {
        is File -> value
        is org.gradle.api.file.RegularFile -> value.asFile
        else -> error("cannot interpret $value as a file")
    }

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
