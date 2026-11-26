package xyz.wagyourtail.unimined.remap

import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
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
 *
 * ## Correctness flags vs convenience knobs
 *
 * Spec fields come in two categories:
 *
 * - **Convenience knobs** (`emitRefmap`, `noRefmapConfigs`,
 *   `forceMixinTables`, `mixinRemapOff`) — an explicit
 *   `modArtifacts { }` declaration takes full control of these;
 *   loader-contributed convenience conventions only ever shape the
 *   auto-appended default step.
 * - **Correctness flags** (today only
 *   [remapLaunchwrapperTransformerLdcs], tracked in [CorrectnessFlag])
 *   — flags a loader needs on for its mod artifacts to boot at all.
 *   These have true *convention semantics*: loader wirings pre-seed
 *   them even on explicit `modArtifacts { }` declarations, unless the
 *   user explicitly opted out. Every user-strength write — the
 *   `enable*`/`disable*` verb or direct property assignment — records
 *   the flag in [explicitlyConfiguredCorrectnessFlags]; loader wirings
 *   write through the `preseed*` methods, which are no-ops once the
 *   user has spoken. A deliberate opt-out therefore looks like:
 *
 *   ```kotlin
 *   modArtifacts {
 *       disableLaunchwrapperTransformerLdcRemap()
 *   }
 *   ```
 *
 * ### Adding a new correctness-critical flag
 *
 * 1. Add a [CorrectnessFlag] enum entry.
 * 2. Back the property with a private field; the public setter writes
 *    the backing field AND records the enum entry in
 *    `userConfiguredCorrectnessFlags` (route `enable*`/`disable*`
 *    verbs through the property).
 * 3. Add a `preseed*` method that writes the backing field directly
 *    through [preseed] — never the public setter — so convention
 *    application neither clobbers a user opt-out nor masquerades as
 *    a user write.
 */
class ModArtifactsStepSpec(
    @get:Input @get:Optional override val engine: String? = null
) : ModArtifactOnlyStepSpec {

    /**
     * Correctness-critical flags with true convention semantics — see
     * the class KDoc. Loader wirings pre-seed these even on explicit
     * `modArtifacts { }` declarations unless the user explicitly
     * configured the flag themselves.
     */
    enum class CorrectnessFlag {
        /** Backing flag of [ModArtifactsStepSpec.remapLaunchwrapperTransformerLdcs]. */
        LAUNCHWRAPPER_TRANSFORMER_LDCS
    }

    /**
     * Correctness flags the user explicitly configured — enable,
     * disable, or direct property assignment all count. Loader-wiring
     * `preseed*` writes do not. Serialized with the spec but not an
     * input: explicitness is configuration-time bookkeeping, the
     * materialized flag values carry the behavioral state.
     */
    @get:Internal
    val explicitlyConfiguredCorrectnessFlags: Set<CorrectnessFlag>
        get() = userConfiguredCorrectnessFlags.toSet()

    private val userConfiguredCorrectnessFlags: MutableSet<CorrectnessFlag> =
        java.util.EnumSet.noneOf(CorrectnessFlag::class.java)

    /** Convention-strength write helper — runs [write] only when the
     *  user never spoke for [flag]. Correctness pre-seeds must be
     *  convergent: the same spec object can materialize through several
     *  pipeline projections, so a pre-seed is idempotent by contract. */
    private fun preseed(flag: CorrectnessFlag, write: () -> Unit) {
        if (flag !in userConfiguredCorrectnessFlags) write()
    }

    /** 1.x `mixinRemap { off() }` — skip the mixin/annotation-table
     *  machinery (mixin config rewrite + refmaps, liteloader baked
     *  obf tables, JarModAgent/ClassTransform refmaps). Kotlin
     *  metadata rewriting is not mixin-scoped and still runs. */
    @get:Input
    var mixinRemapOff: Boolean = false

    /** Source-namespace internal names of classes whose `ldc` string
     *  constants may be rewritten as class names/descs. Default empty —
     *  this pass is opt-in and class-scoped, matching 1.x's forge-only
     *  string remapper. Universal string surgery is unsafe for protocols
     *  such as NilLoader's runtime remap table, whose inputs must remain
     *  in the compile namespace. */
    @get:Input
    var classNameLdcClasses: Set<String> = emptySet()

    /** Whether class-name/desc `ldc` rewriting is active. Derived from
     *  [classNameLdcClasses] and [remapLaunchwrapperTransformerLdcs];
     *  retained as the boolean input companion to the opt-in list. */
    @get:Input
    val remapClassNameLdcs: Boolean
        get() = classNameLdcClasses.isNotEmpty() || remapLaunchwrapperTransformerLdcs

    private var launchwrapperTransformerLdcsBacking: Boolean = false

    /** Detect classes implementing `net.minecraft.launchwrapper.IClassTransformer`
     *  and rewrite class-name/descriptor `ldc` strings in just those classes.
     *  LaunchWrapper coremod transformers use such literals to construct the
     *  bytecode they inject; unlike arbitrary mod classes, that is a binary
     *  class-reference channel rather than a runtime mapping protocol. Loader
     *  wirings opt in where LaunchWrapper coremods are a supported artifact
     *  shape (legacy forge); the default remains off.
     *
     *  This is a **correctness flag** — loader wirings pre-seed it even on
     *  explicit `modArtifacts { }` declarations (see the class KDoc). Any
     *  user-strength write through this setter — including the
     *  `enable`/`disable` verbs — records the explicit configuration, so a
     *  deliberate `= false` / [disableLaunchwrapperTransformerLdcRemap]
     *  opt-out is never rewritten by conventions. */
    @get:Input
    var remapLaunchwrapperTransformerLdcs: Boolean
        get() = launchwrapperTransformerLdcsBacking
        set(value) {
            launchwrapperTransformerLdcsBacking = value
            userConfiguredCorrectnessFlags += CorrectnessFlag.LAUNCHWRAPPER_TRANSFORMER_LDCS
        }

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

    /** Opt in to `ldc` class-name/desc string rewriting for the named
     *  source-namespace internal classes (slash form, e.g.
     *  `com/example/MyTransformer`). Only those classes are scanned. */
    fun enableClassNameLdcRemap(vararg classes: String) {
        classNameLdcClasses += classes
    }

    /** Groovy DSL twin of [enableClassNameLdcRemap]. */
    fun enableClassNameLdcRemap(classes: Set<String>) {
        classNameLdcClasses += classes
    }

    /** Clear the class-name/desc `ldc` opt-in list. NilLoader wiring can
     *  contribute this defensively; the empty default already means off. */
    fun disableClassNameLdcRemap() {
        classNameLdcClasses = emptySet()
    }

    /** Opt in to LaunchWrapper-transformer-scoped `ldc` rewriting. */
    fun enableLaunchwrapperTransformerLdcRemap() {
        remapLaunchwrapperTransformerLdcs = true
    }

    /** Opt out of LaunchWrapper-transformer-scoped `ldc` rewriting —
     *  an explicit user opt-out, so loader correctness pre-seeds will
     *  not re-enable it (unlike convenience knobs, correctness flags
     *  survive explicit `modArtifacts { }` declarations unless the
     *  user speaks for the flag itself). */
    fun disableLaunchwrapperTransformerLdcRemap() {
        remapLaunchwrapperTransformerLdcs = false
    }

    /** Loader-wiring correctness pre-seed for
     *  [remapLaunchwrapperTransformerLdcs] — turns the pass on unless
     *  the user explicitly enabled or disabled it in their own
     *  `modArtifacts { }` declaration. Transform wirings must use this
     *  inside correctness conventions rather than
     *  [enableLaunchwrapperTransformerLdcRemap] — the enable verb is a
     *  user-strength write and would clobber deliberate opt-outs (and
     *  misrecord the convention as a user configuration). */
    fun preseedLaunchwrapperTransformerLdcRemap() =
        preseed(CorrectnessFlag.LAUNCHWRAPPER_TRANSFORMER_LDCS) {
            launchwrapperTransformerLdcsBacking = true
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
