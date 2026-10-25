package xyz.wagyourtail.unimined.transform.spi

import org.gradle.api.tasks.TaskProvider
import xyz.wagyourtail.unimined.api.spec.RunConfigSpec
import xyz.wagyourtail.unimined.transform.task.TransformMinecraftTask

/**
 * SPI implemented by each `unimined-transform-*` plugin — declared via
 * the transform builder API, never auto-applied by classpath presence.
 *
 * A transform takes a resolved Minecraft artifact (+ mappings, + prior
 * transform outputs) and emits a "dev" artifact: patched, deobfed to
 * named namespace, loader metadata baked.
 */
interface MinecraftTransformer {

    /** Declare what this transform needs and produces. */
    fun configure(ctx: TransformContext)

    /** The task producing this transform's output artifact. */
    val transformTaskProvider: TaskProvider<out TransformMinecraftTask>

    /**
     * Mutate a run config for this environment — forge injects
     * `tweakClass`/launchwrapper args, fabric swaps the main class.
     * (1.x: `applyClientRunTransform`/`applyServerRunTransform`.)
     */
    fun transformRun(config: RunConfigSpec) {}

}
