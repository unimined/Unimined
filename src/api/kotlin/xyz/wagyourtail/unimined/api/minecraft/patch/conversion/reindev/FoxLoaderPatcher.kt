package xyz.wagyourtail.unimined.api.minecraft.patch.conversion.reindev

import groovy.lang.Closure
import groovy.lang.DelegatesTo
import org.gradle.api.artifacts.Dependency
import xyz.wagyourtail.unimined.api.minecraft.patch.MinecraftPatcher

/**
 * The class responsible for patching ReIndev for FoxLoader.
 *
 * usage:
 * ```groovy
 * foxLoader {
 *     loader()
 *     modId = "example-mod"
 *     clientMod = "com.example.example.mod.ExampleClient"
 * }
 * ```
 * @see loader
 * @see modId
 * @see commonMod
 * @see clientMod
 * @see serverMod
 * @since 1.3.5
 */
interface FoxLoaderPatcher : MinecraftPatcher {

    /**
     * Picks the version of the loader automatically, based on the ReIndev version.
     *
     * @since 1.3.5
     */
    fun loader()

    /**
     * @since 1.3.5
     */
    fun loader(dep: Any) {
        loader(dep) {}
    }

    /**
     * Sets the dependency for FoxLoader explicitly.
     * @param dep Either a version number or a dependency notation.
     *
     * @since 1.3.5
     */
    fun loader(dep: Any, action: Dependency.() -> Unit)

    /**
     * Sets the dependency for FoxLoader explicitly.
     * @param dep Either a version number or a dependency notation.
     *
     * @since 1.3.5
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

    /**
     * Path to your mod's common initializer. Must extend from Mod.
     *
     * Example: `com.example.example.mod.ExampleMod`
     * @since 1.3.5
     */
    var commonMod: String

    /**
     * Path to your mod's client initializer. Must extend from Mod and implement ClientMod.
     *
     * Example: `com.example.example.mod.ExampleClient`
     * @since 1.3.5
     */
    var clientMod: String

    /**
     * Path to your mod's server initializer. Must extend from Mod and implement ServerMod.
     *
     * Example: `com.example.example.mod.ExampleServer`
     * @since 1.3.5
     */
    var serverMod: String

    /**
     * Required. The short ID of your mod.
     *
     * Example: `example-mod`
     * @since 1.3.5
     */
    var modId: String

    /**
     * The version number of your mod. Defaults to the project version.
     *
     * Example: `1.0.0`
     * @since 1.3.5
     */
    var modVersion: String

    /**
     * The name of your mod.
     *
     * Example: `Example Mod`
     * @since 1.3.5
     */
    var modName: String

    /**
     * Provided for the convenience of migration
     * @since 1.3.5
     */
    @Deprecated("", replaceWith = ReplaceWith("modDescription"))
    var modDesc: String

    /**
     * The description of your mod. This must fit on one line in the mod list screen.
     *
     * Example: `An example mod for FoxLoader!`
     * @since 1.3.5
     */
    var modDescription: String

    /**
     * The web homepage for your mod.
     *
     * Example: `https://example.com/example-mod`
     * @since 1.3.5
     */
    var modWebsite: String

    /**
     * The entrypoint for your mod's ASM transformations. Must implement PreClassTransformer.
     *
     * Example: `com.example.example.mod.transformer.ExamplePreClassTransformer`
     * @since 1.3.5
     */
    var preClassTransformer: String

    /**
     *
     * Example: `com.example.example.mod.plugin.ExampleFoxLoaderPlugin`
     *
     * @since 1.3.5
     */
    var loadingPlugin: String

    /**
     * Adds the "unofficial" tag to your mod in the mod list screen.
     * @since 1.3.5
     */
    var unofficial: Boolean
}
