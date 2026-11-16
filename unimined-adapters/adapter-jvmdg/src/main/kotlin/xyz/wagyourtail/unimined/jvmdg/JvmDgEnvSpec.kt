package xyz.wagyourtail.unimined.jvmdg

import groovy.lang.Closure
import groovy.lang.DelegatesTo
import org.gradle.api.Action
import org.gradle.api.plugins.ExtensionAware
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional
import xyz.wagyourtail.jvmdg.gradle.flags.ShadeFlags
import xyz.wagyourtail.unimined.api.util.delegatingTo
import xyz.wagyourtail.unimined.minecraft.MinecraftEnvironment
import xyz.wagyourtail.unimined.minecraft.MinecraftExtension

/**
 * Per-environment JvmDowngrader settings, contributed by the
 * `xyz.wagyourtail.unimined.jvmdg` plugin as an extension named
 * `jvmdowngrader` on each [MinecraftEnvironment] **and** on the
 * [MinecraftExtension] itself — the extension-level spec is the
 * global layer: `minecraft { jvmdowngrader { source.set(21) } }`
 * sets defaults for every env, per-env specs override it.
 * **Off by default** — applying the plugin only makes the DSL
 * available; envs opt in by configuring their own spec or the
 * global one (`enabled` set at either scope), or explicitly.
 *
 * Value precedence for every flag: an env's own set, then its
 * inherited envs (nearest first), then the global spec, then the
 * built-in default.
 *
 * When enabled, the env's dev `run*` tasks launch against
 * JvmDowngrader-processed copies of `sourceSet.output.classesDirs`
 * instead of the raw compile outputs, so loaders whose bundled ASM
 * cannot read modern class files (FG-era LaunchWrapper/FML reads
 * ≤v52; ≤1.6.4-era reads ≤v51) can run modern-compiled mods. The
 * same downgrade is contributed to the env's production jar
 * pipeline, so `runProd`/published artifacts get the emit contract
 * dev runs get.
 *
 * The spec implements jvmdg's Gradle-frontend [ShadeFlags], so its
 * engine-level knobs (`ignoreWarningsIn`, `logLevel`,
 * `multiReleaseVersions`, …) are settable directly on the
 * `jvmdowngrader { }` block and flow to both the dev-class and
 * prod-jar downgrades. Prefer [target] over [ShadeFlags.downgradeTo]
 * — the conventions chain (`target` is the era floor as an int,
 * `downgradeTo` inherits it as a [org.gradle.api.JavaVersion]) and a
 * direct `downgradeTo` set wins over `target`.
 */
abstract class JvmDgEnvSpec : ShadeFlags {

    /**
     * Whether class-file downgrading is active for this environment —
     * **off by default**; invoking `jvmdowngrader { }` or
     * `jvmdowngrader(N)` opts the env in, `enabled.set(false)` opts it
     * back out (explicitly — including inside the configuring block).
     */
    @get:Input
    abstract val enabled: Property<Boolean>

    /**
     * The Java level the environment's `compile*` tasks emit at —
     * feeds [MinecraftEnvironment.compileJavaVersion] when set,
     * replacing the [MinecraftEnvironment.requiredJavaVersion]
     * default: `jvmdowngrader { source.set(21); target.set(8) }` on a
     * 1.12-era env compiles sources against Java 21 APIs while the
     * run JVM and emitted bytecode stay at the era floor. Unset, the
     * env compiles at its requirement and the downgrade is a
     * passthrough.
     */
    @get:Optional
    @get:Input
    abstract val source: Property<Int>

    /**
     * Class-file target as a Java major version (`8` → v52, `7` →
     * v51). Conventions to the environment's Minecraft Java floor —
     * the version-json/capability `javaVersion` (deliberately *not*
     * [MinecraftEnvironment.requiredJavaVersion], which a raised
     * `javaVersion` inflates: the point of downgrading is to compile
     * newer while emitting bytecode the era runtime can read) — with
     * ≤1.6.4-era versions floored at 7.
     */
    @get:Optional
    @get:Input
    abstract val target: Property<Int>

}

/**
 * DSL accessor for the env's `jvmdowngrader` extension:
 * `sourceSet("main") { jvmdowngrader { target.set(8) } }`. Invoking it
 * opts the environment in ([JvmDgEnvSpec.enabled] is set ahead of the
 * block, so `jvmdowngrader { enabled.set(false) }` still wins).
 */
fun MinecraftEnvironment.jvmdowngrader(action: Action<JvmDgEnvSpec>): JvmDgEnvSpec =
    extensions.getByType(JvmDgEnvSpec::class.java).also { spec ->
        spec.enabled.set(true)
        action.execute(spec)
    }

/** Groovy DSL form of [jvmdowngrader]. */
fun MinecraftEnvironment.jvmdowngrader(
    @DelegatesTo(value = JvmDgEnvSpec::class, strategy = Closure.DELEGATE_FIRST)
    action: Closure<*>
): JvmDgEnvSpec = jvmdowngrader(Action { action.delegatingTo(it) })

/** `jvmdowngrader(8)` — shorthand: enables and sets
 *  [JvmDgEnvSpec.target]. */
fun MinecraftEnvironment.jvmdowngrader(target: Int): JvmDgEnvSpec =
    jvmdowngrader(Action {
        it.target.set(target)
    })

/** * The `minecraft {}` block's `jvmdowngrader` is the **global**
 * spec — `unimined { minecraft { jvmdowngrader { source.set(21) } } }`
 * sets defaults for every environment in the extension (and opts
 * them all in unless one sets `enabled` back off). Configure a
 * single env via `sourceSet("client") { jvmdowngrader { } }`.
 */
fun MinecraftExtension.jvmdowngrader(action: Action<JvmDgEnvSpec>): JvmDgEnvSpec =
    (this as ExtensionAware).extensions.getByType(JvmDgEnvSpec::class.java)
        .also { spec ->
            spec.enabled.set(true)
            action.execute(spec)
        }

/** Groovy DSL form of [jvmdowngrader]. */
fun MinecraftExtension.jvmdowngrader(
    @DelegatesTo(value = JvmDgEnvSpec::class, strategy = Closure.DELEGATE_FIRST)
    action: Closure<*>
): JvmDgEnvSpec = jvmdowngrader(Action { action.delegatingTo(it) })

/** `minecraft { jvmdowngrader(8) }` — [jvmdowngrader] globally. */
fun MinecraftExtension.jvmdowngrader(target: Int): JvmDgEnvSpec =
    jvmdowngrader(Action { it.target.set(target) })
