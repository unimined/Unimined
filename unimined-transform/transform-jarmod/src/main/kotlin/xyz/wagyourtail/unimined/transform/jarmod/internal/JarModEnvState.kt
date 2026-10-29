package xyz.wagyourtail.unimined.transform.jarmod.internal

/**
 * Per-env jarmod DSL state collected at configuration time — the
 * 1.x patcher fields (`transforms`, `priorityClasspath`,
 * `compiletimeTransforms`) the run/transform wiring consumes.
 * Keyed by environmentId, populated by `JarModDsl` ext fns.
 */
internal object JarModEnvState {

    private val transforms = mutableMapOf<String, MutableList<String>>()
    private val priorityClasspath = mutableMapOf<String, MutableList<String>>()

    fun addTransforms(envId: String, entries: Iterable<String>) {
        transforms.getOrPut(envId) { mutableListOf() } += entries
    }

    fun addPriorityClasspath(envId: String, entries: Iterable<String>) {
        priorityClasspath.getOrPut(envId) { mutableListOf() } += entries
    }

    fun transforms(envId: String): List<String> = transforms[envId] ?: emptyList()
    fun priorityClasspath(envId: String): List<String> = priorityClasspath[envId] ?: emptyList()

}
