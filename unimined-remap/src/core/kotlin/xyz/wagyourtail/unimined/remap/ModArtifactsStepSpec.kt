package xyz.wagyourtail.unimined.remap

import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional
import xyz.wagyourtail.unimined.remap.spi.ModArtifactOnlyStepSpec

/**
 * DSL spec of the mod-artifact post-remap step — rewrites mod-jar
 * metadata that a pure namespace remap leaves untouched:
 * `kotlin.Metadata` internal names, mixin configs + refmap emission
 * (`*.mixins.json` inputs), and JarModAgent/ClassTransform refmaps
 * (manifest `JarModAgent-Refmaps`).
 *
 * Runs as an extension pass after the namespace `remap` step on the
 * [RemapSurface.JAR] pipeline — engine-agnostic, so a `remap("tiny")`
 * override or a remapped dependency view gets the same treatment as the
 * default `uml` engine. Added implicitly to JAR pipelines containing a
 * remap step; declare explicitly via `jar { modArtifacts() }` to pin an
 * engine or ordering.
 */
class ModArtifactsStepSpec(
    @get:Input @get:Optional override val engine: String? = null
) : ModArtifactOnlyStepSpec {

    /** 1.x `mixinRemap { off() }` — skip the mixin/annotation-table
     *  machinery (mixin config rewrite + refmaps, liteloader baked
     *  obf tables, JarModAgent/ClassTransform refmaps). Kotlin
     *  metadata rewriting is not mixin-scoped and still runs. */
    @get:Input
    var mixinRemapOff: Boolean = false

    /** Refmap JSON emission — [disableRefmap] flips it off for every
     *  config (1.x `disableRefmap()` covering all readers). */
    @get:Input
    var emitRefmap: Boolean = true

    /** `*.mixins.json` config paths whose refmap emission is
     *  suppressed — the 2.0 shape of 1.x `disableRefmap(keys)`: keys
     *  name configs (`"mymod.mixins.json"`), not metadata readers
     *  (those don't exist as a concept anymore). */
    @get:Input
    var noRefmapConfigs: Set<String> = emptySet()

    /** Force-enabled annotation tables — `"mixin"`, `"mixinextras"`,
     *  `"jma"` — mapped from 1.x
     *  `enableBaseMixin`/`enableMixinExtra`/`enableJarModAgent`.
     *  Non-empty adds the extension even when its resource gate
     *  (a `*.mixins.json` entry / manifest `JarModAgent-Refmaps`)
     *  is absent. Empty = auto-detect (all gates on). */
    @get:Input
    var forceMixinTables: Set<String> = emptySet()

    /** 1.x `mixinRemap { off() }`. */
    fun off() {
        mixinRemapOff = true
    }

    /** 1.x `mixinRemap { disableRefmap() }` — no refmap emission. */
    fun disableRefmap() {
        emitRefmap = false
    }

    /** Suppress refmap emission for the named config paths. */
    fun disableRefmap(vararg configs: String) {
        noRefmapConfigs += configs
    }

    /** Groovy DSL twin of [disableRefmap]. */
    fun disableRefmap(configs: Set<String>) {
        noRefmapConfigs += configs
    }

    /** 1.x `enableBaseMixin` — force the sponge mixin table. */
    fun enableBaseMixin() {
        forceMixinTables += "mixin"
    }

    /** 1.x `enableMixinExtra` — force the MixinExtras table. */
    fun enableMixinExtra() {
        forceMixinTables += "mixinextras"
    }

    /** 1.x `enableJarModAgent` — force the JMA/ClassTransform table. */
    fun enableJarModAgent() {
        forceMixinTables += "jma"
    }

}
