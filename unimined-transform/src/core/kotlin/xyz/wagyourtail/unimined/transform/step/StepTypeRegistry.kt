package xyz.wagyourtail.unimined.transform.step

import java.util.ServiceLoader

/**
 * Registry of pipeline [StepType]s — ServiceLoader-discovered
 * [StepTypeSource] providers (loader feature packs) plus manually
 * registered types. Marker vocabulary is owned here: a marker with
 * no registered producing step type fails to resolve rather than
 * silently never gating.
 */
class StepTypeRegistry {

    private val registered = mutableListOf<StepType>()
    private val markerOwners = mutableMapOf<String, String>()

    /** Register a step type. Marker collisions fail loudly —
     *  two types producing the same marker make provider-reported
     *  prefixes ambiguous. */
    fun register(stepType: StepType) {
        registered += stepType
        for (marker in stepType.produces) {
            val prior = markerOwners.put(marker, stepType.id)
            require(prior == null || prior == stepType.id) {
                "stage marker '$marker' claimed by both $prior and ${stepType.id}"
            }
        }
    }

    /** Every known type, builtins first, in registration order. */
    val all: List<StepType>
        get() = builtins() + registered

    /** Resolve a step type by namespaced id, or null. */
    fun resolve(id: String): StepType? = all.firstOrNull { it.id == id }

    /** The step type id that produces [marker], or null. */
    fun markerOwner(marker: String): String? = markerOwners[marker]
        ?: builtins().firstOrNull { marker in it.produces }?.id

    /**
     * Topologically sort [steps] (dedup'd, resolution order
     * preserved) over each type's [StepType.requires]/
     * [StepType.runsAfter] hints. Unconstrained steps keep
     * registration order. Missing required deps throw.
     */
    fun <T : StepType> resolveOrder(steps: List<T>): List<T> {
        val ids = steps.mapTo(HashSet()) { it.id }
        val resolved = mutableListOf<T>()
        val pending = steps.toMutableList()
        val seen = mutableSetOf<String>()
        while (pending.isNotEmpty()) {
            val before = pending.size
            val iter = pending.iterator()
            while (iter.hasNext()) {
                val step = iter.next()
                for (req in step.requires) {
                    require(req in ids) {
                        "${step.id} requires $req, which is not in the declared pipeline"
                    }
                }
                val depsSatisfied = step.requires.all { it in seen } &&
                        step.runsAfter.all { it !in ids || it in seen }
                if (depsSatisfied) {
                    resolved += step
                    seen += step.id
                    iter.remove()
                }
            }
            require(pending.size < before) {
                "cycle or unsatisfiable ordering hints among: " +
                        pending.joinToString { it.id }
            }
        }
        return resolved
    }

    companion object {

        /** ServiceLoader scan over [StepTypeSource] providers. */
        fun builtins(): List<StepType> =
            (ServiceLoader.load(
                StepTypeSource::class.java,
                StepTypeSource::class.java.classLoader
            ) + runCatching {
                ServiceLoader.load(StepTypeSource::class.java)
            }.getOrDefault(ServiceLoader.load(
                StepTypeSource::class.java,
                StepTypeSource::class.java.classLoader
            ))).flatMap { it.stepTypes() }

    }

}

/** ServiceLoader SPI — loader modules expose their step types here. */
interface StepTypeSource {

    fun stepTypes(): List<StepType>

}
