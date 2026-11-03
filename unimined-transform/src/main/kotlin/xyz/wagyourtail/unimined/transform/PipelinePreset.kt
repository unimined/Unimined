package xyz.wagyourtail.unimined.transform

import java.util.ServiceLoader

/**
 * A named pipeline preset — a canonical declared step list a loader
 * ships for `pipeline { era("userdev") }` expansion. Presets are
 * declared by transform modules via [PipelinePresetSource]
 * ServiceLoader (forge's `"userdev"` expands to its `userdevExec`
 * step; `"binpatches"` to the merge/binpatch/AT chain).
 *
 * Probe-driven era selection composes [PipelineSpec.select] with
 * presets — `select("era") { branch("userdev") { era("userdev") } }`
 * — keeping preset expansion unconditional while the probe picks
 * which branch expands.
 */
interface PipelinePreset {

    /** Preset name — `era("name")` key. */
    val name: String

    /** Expand this preset's declared steps into [pipeline]. */
    fun expand(pipeline: PipelineSpec)

}

/** ServiceLoader source for [PipelinePreset]s. */
interface PipelinePresetSource {

    fun presets(): List<PipelinePreset>

}

/**
 * Registry of pipeline presets — ServiceLoader builtins plus manual
 * registration (mirrors [xyz.wagyourtail.unimined.transform.feature.FeatureRegistry]).
 * Unknown preset names fail at `era("name")` declaration time.
 */
class PipelinePresetRegistry {

    private val registered = mutableMapOf<String, PipelinePreset>()

    /** Register or shadow a preset by name. */
    fun register(preset: PipelinePreset) {
        registered[preset.name] = preset
    }

    fun resolve(name: String): PipelinePreset? =
        registered[name] ?: builtins()[name]

    fun require(name: String): PipelinePreset = resolve(name)
        ?: throw IllegalArgumentException(
            "unknown pipeline preset '$name' — no PipelinePresetSource " +
                    "provides it; check the transform module is on the classpath"
        )

    companion object {

        private var builtinsCache: Map<String, PipelinePreset>? = null

        fun builtins(): Map<String, PipelinePreset> =
            builtinsCache ?: (ServiceLoader.load(
                PipelinePresetSource::class.java,
                PipelinePresetSource::class.java.classLoader
            ) + runCatching {
                ServiceLoader.load(PipelinePresetSource::class.java)
            }.getOrDefault(emptyList()))
                .flatMap { it.presets() }
                .associateBy { it.name }
                .also { builtinsCache = it }

    }

}
