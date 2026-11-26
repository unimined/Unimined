package xyz.wagyourtail.unimined.mod

import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property

/**
 * Per-era overrides for [EraMergeSpec]. An override never selects the era by
 * itself — selection comes from the automatic environment set or an explicit
 * `eras(...)` list — except that an override carrying [jar] and no bound
 * environment is treated as a manual era input.
 */
abstract class EraInputSpec {

    /**
     * Stringly partition override: `"nest"` or `"flatten"`. Unset uses the
     * loader descriptor's nested-jar capability (fabric-family metadata keys)
     * and falls back to `"flatten"`.
     */
    abstract val partition: Property<String>

    /** Loader id used for capability-based partitioning of manual jar inputs. */
    abstract val loader: Property<String>

    /**
     * Explicit production artifact for this era. When unset, unimined selects
     * the environment's `remap<Env>Jar` artifact, or the plain `<Env>Jar` for
     * identity-remap environments.
     */
    abstract val jar: RegularFileProperty

    /** Stringly setter for [partition], convenient in Groovy DSL. */
    fun partition(value: String) = partition.set(value)

    /** Stringly setter for [loader], convenient in Groovy DSL. */
    fun loader(value: String) = loader.set(value)

}
