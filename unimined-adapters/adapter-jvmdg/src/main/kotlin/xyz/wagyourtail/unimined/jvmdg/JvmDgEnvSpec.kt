package xyz.wagyourtail.unimined.jvmdg

import org.gradle.api.Action
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional
import xyz.wagyourtail.unimined.minecraft.MinecraftEnvironment
import xyz.wagyourtail.unimined.minecraft.MinecraftExtension

/**
 * Per-environment JvmDowngrader settings, contributed by the
 * `xyz.wagyourtail.unimined.jvmdg` plugin as an extension named
 * `jvmdowngrader` on each [MinecraftEnvironment]. **On by default**
 * — applying the plugin is the opt-in, so every environment
 * downgrades unless `enabled.set(false)` opts it back out.
 *
 * When enabled, the env's dev `run*` tasks launch against
 * JvmDowngrader-processed copies of `sourceSet.output.classesDirs`
 * instead of the raw compile outputs, so loaders whose bundled ASM
 * cannot read modern class files (FG-era LaunchWrapper/FML reads
 * ≤v52; ≤1.6.4-era reads ≤v51) can run modern-compiled mods.
 */
abstract class JvmDgEnvSpec {

    /**
     * Whether dev-run class-file downgrading is active for this
     * environment — **the opt-out flag, default on** when the
     * `xyz.wagyourtail.unimined.jvmdg` plugin is applied. Set
     * `jvmdowngrader { enabled.set(false) }` to exempt a single
     * environment.
     */
    @get:Input
    abstract val enabled: Property<Boolean>

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
 * `sourceSet("main") { jvmdowngrader { target.set(8) } }`.
 */
fun MinecraftEnvironment.jvmdowngrader(action: Action<JvmDgEnvSpec>): JvmDgEnvSpec =
    extensions.getByType(JvmDgEnvSpec::class.java).also { action.execute(it) }

/** `jvmdowngrader(8)` — shorthand: sets [JvmDgEnvSpec.target]
 *  (enabled is already on by default). */
fun MinecraftEnvironment.jvmdowngrader(target: Int): JvmDgEnvSpec =
    jvmdowngrader(Action {
        it.target.set(target)
    })

/**
 * The `minecraft {}` block configures the `main` environment — these
 * forward there so `unimined { minecraft { jvmdowngrader(8) } }` works
 * alongside the per-env `sourceSet("client") { jvmdowngrader { } }`
 * form for non-main envs.
 */
fun MinecraftExtension.jvmdowngrader(action: Action<JvmDgEnvSpec>): JvmDgEnvSpec =
    environments.getByName("main").jvmdowngrader(action)

/** `minecraft { jvmdowngrader(8) }` — [jvmdowngrader] on `main`. */
fun MinecraftExtension.jvmdowngrader(target: Int): JvmDgEnvSpec =
    environments.getByName("main").jvmdowngrader(target)
