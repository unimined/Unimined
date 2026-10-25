package xyz.wagyourtail.unimined

import groovy.lang.Closure
import groovy.lang.DelegatesTo
import org.gradle.api.Action
import org.gradle.api.Project
import javax.inject.Inject
import xyz.wagyourtail.unimined.minecraft.MinecraftExtension
import xyz.wagyourtail.unimined.remap.RemapExtension
import xyz.wagyourtail.unimined.transform.TransformExtension

/**
 * The `unimined { }` facade — an aggregator presenting one DSL that fans
 * out to the underlying plugins' extensions. Each underlying plugin
 * remains independently applicable; this only folds them into one
 * interface. Transform-agnostic: transforms declare themselves through
 * the transform builder API on [TransformExtension].
 */
abstract class UniminedExtension @Inject constructor(private val project: Project) {

    /** `unimined.minecraft { }` → the `unimined-minecraft` extension. */
    fun minecraft(action: Action<MinecraftExtension>) {
        action.execute(project.extensions.getByType(MinecraftExtension::class.java))
    }

    /** Groovy DSL overload; delegates the closure to the Minecraft extension. */
    fun minecraft(
        @DelegatesTo(value = MinecraftExtension::class, strategy = Closure.DELEGATE_FIRST)
        action: Closure<*>
    ) {
        val extension = project.extensions.getByType(MinecraftExtension::class.java)
        action.delegate = extension
        action.resolveStrategy = Closure.DELEGATE_FIRST
        action.call()
    }

    /** `unimined.remap { }` → the `unimined-remap` extension. */
    fun remap(action: Action<RemapExtension>) {
        action.execute(project.extensions.getByType(RemapExtension::class.java))
    }

    /** Groovy DSL overload; delegates the closure to the remap extension. */
    fun remap(
        @DelegatesTo(value = RemapExtension::class, strategy = Closure.DELEGATE_FIRST)
        action: Closure<*>
    ) {
        val extension = project.extensions.getByType(RemapExtension::class.java)
        action.delegate = extension
        action.resolveStrategy = Closure.DELEGATE_FIRST
        action.call()
    }

    /** `unimined.transform { }` → the `unimined-transform` extension. */
    fun transform(action: Action<TransformExtension>) {
        action.execute(project.extensions.getByType(TransformExtension::class.java))
    }

    /** Groovy DSL overload; delegates the closure to the transform extension. */
    fun transform(
        @DelegatesTo(value = TransformExtension::class, strategy = Closure.DELEGATE_FIRST)
        action: Closure<*>
    ) {
        val extension = project.extensions.getByType(TransformExtension::class.java)
        action.delegate = extension
        action.resolveStrategy = Closure.DELEGATE_FIRST
        action.call()
    }

}
