package xyz.wagyourtail.unimined.api.minecraft.patch.fabric

import groovy.lang.Closure
import groovy.lang.DelegatesTo
import org.gradle.api.artifacts.Dependency
import org.jetbrains.annotations.ApiStatus
import xyz.wagyourtail.unimined.api.minecraft.patch.MinecraftPatcher
import xyz.wagyourtail.unimined.api.minecraft.patch.ataw.AccessWidenerPatcher
import xyz.wagyourtail.unimined.mapping.Namespace
import java.nio.file.Path

/**
 * The class responsible for patching minecraft for fabric.
 *
 * usage:
 * ```groovy
 * fabric {
 *     loader "0.14.20"
 *     accessWidener "src/main/resources/myAccessWidener.cfg"
 *
 *     /* use these if intermediary mappings aren't a thing for your version */
 *     prodNamespace "official"
 *     devMappings = null
 * }
 * ```
 * @since 0.2.3
 */
interface FabricLikePatcher: MinecraftPatcher, AccessWidenerPatcher {

    /**
     * 0.4.10 - make var for beta's and other official mapped versions
     * @since 0.2.3
     */
    @get:ApiStatus.Internal
    override var prodNamespace: Namespace

    /**
     * @since 1.0.0
     */
    @get:ApiStatus.Internal
    @set:ApiStatus.Experimental
    var devMappings: Path?

    @set:ApiStatus.Experimental
    var customIntermediaries: Boolean

    /**
     * Whether the user *or* the Unimined game provider
     * has provided a custom **Fabric game provider**
     * and should skip over trying to use the built-in Minecraft game provider.
     *
     * @since 1.4.0
     */
    var customGameProvider: Boolean

    /**
     * @since 1.0.0
     */
    fun loader(dep: Any) {
        loader(dep) {}
    }

    /**
     * set the version of fabric loader to use
     * must be called
     * @since 1.0.0
     */
    fun loader(dep: Any, action: Dependency.() -> Unit)

    /**
     * @since 1.0.0
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
     * @since 0.4.10
     */
    @Deprecated(message = "", replaceWith = ReplaceWith("prodNamespace(namespace)"))
    fun setProdNamespace(namespace: String)

    /**
     * @since 1.0.0
     */
    override fun prodNamespace(namespace: String)

    var skipInsertAw: Boolean
}