package xyz.wagyourtail.unimined.api.minecraft.task

import groovy.lang.Closure
import groovy.lang.DelegatesTo
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional
import xyz.wagyourtail.unimined.api.mapping.mixin.MixinRemapOptions
import xyz.wagyourtail.unimined.util.JarInterface

interface RemapJarTask : JarInterface<AbstractRemapJarTask>, RemapOptions {

    /**
     * whether to remap AccessTransformers to the legacy format (<=1.7.10)
     */
    @get:Input
    @get:Optional
    val remapATToLegacy: Property<Boolean?>

    /**
     * Enable remapping of access wideners in the jar.
     * Enabled by default
     * @since 1.4.1
     */
    @get:Input
    @get:Optional
    val remapAccessWidener: Property<Boolean?>

    /**
     * Enable remapping of access transformers in the jar.
     * Enabled by default
     * @since 1.4.1
     */
    @get:Input
    @get:Optional
    val remapAccessTransformer: Property<Boolean?>

    fun mixinRemap(action: MixinRemapOptions.() -> Unit)

    fun mixinRemap(
        @DelegatesTo(value = MixinRemapOptions::class, strategy = Closure.DELEGATE_FIRST)
        action: Closure<*>
    ) {
        mixinRemap {
            action.delegate = this
            action.resolveStrategy = Closure.DELEGATE_FIRST
            action.call()
        }
    }

}