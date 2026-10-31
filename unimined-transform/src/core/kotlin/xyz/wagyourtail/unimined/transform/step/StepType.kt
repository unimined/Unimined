package xyz.wagyourtail.unimined.transform.step

/**
 * A registered transform-pipeline step type — the vocabulary a
 * declared `pipeline { }` resolves against. Loader modules contribute
 * their step types as data (a "feature pack"), so `custom` and any
 * other pipeline can consume them without a private transform.
 *
 * [id] is namespaced `family:name` (`forge:binpatch`,
 * `paperclip:libraryExtract`, `jarmod:merge`) — unnamespaced lookups
 * fail at registration resolution, not at task time.
 */
interface StepType {

    /** Namespaced step id — `family:name`. */
    val id: String

    /** Stage markers this step appends to the artifact's state log
     *  when it runs (or reports pre-executed when the provider
     *  already produced them). */
    val produces: List<String>
        get() = listOf(id)

    /** Declared ordering hints — step ids this step requires to have
     *  run (topo-sort edges; unconstrained steps fall back to
     *  registration order). */
    val requires: Set<String>
        get() = emptySet()

    /** Declared ordering hints — weaker than [requires]: run after
     *  these when present, ignore when absent. */
    val runsAfter: Set<String>
        get() = emptySet()

}
