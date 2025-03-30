package xyz.wagyourtail.unimined.api.minecraft.patch.liteloader

import groovy.lang.Closure
import groovy.lang.DelegatesTo
import org.gradle.api.artifacts.Dependency
import xyz.wagyourtail.unimined.api.minecraft.patch.MinecraftPatcher

/**
 * The class responsible for patching minecraft for liteloader
 * @since 1.4.0
 */
interface LiteLoaderPatcher : MinecraftPatcher {

    /**
     * @since 1.4.0
     */
    fun loader(dep: Any) {
        loader(dep) {}
    }

    /**
     * set the version of liteloader to use
     * must be called
     */
    fun loader(dep: Any, action: Dependency.() -> Unit)

    /**
     * @since 1.4.0
     */
    fun loader(
        dep: Any,
        @DelegatesTo(
            value = Dependency::class,
            strategy = Closure.DELEGATE_FIRST
        ) action: Closure<*>
    ) {
        loader(dep) {
            action.delegate = this
            action.resolveStrategy = Closure.DELEGATE_FIRST
            action.call()
        }
    }

}