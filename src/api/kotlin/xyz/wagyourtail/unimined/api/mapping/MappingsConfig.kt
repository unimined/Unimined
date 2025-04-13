package xyz.wagyourtail.unimined.api.mapping

import groovy.lang.Closure
import groovy.lang.DelegatesTo
import kotlinx.coroutines.runBlocking
import net.fabricmc.tinyremapper.IMappingProvider
import org.gradle.api.Project
import org.jetbrains.annotations.ApiStatus
import xyz.wagyourtail.unimined.api.mapping.dsl.MappingDSL
import xyz.wagyourtail.unimined.api.mapping.dsl.MemoryMapping
import xyz.wagyourtail.unimined.api.minecraft.MinecraftConfig
import xyz.wagyourtail.unimined.mapping.Namespace
import xyz.wagyourtail.unimined.mapping.resolver.MappingResolver
import xyz.wagyourtail.unimined.mapping.tree.MemoryMappingTree
import xyz.wagyourtail.unimined.mapping.util.Scoped
import xyz.wagyourtail.unimined.util.FinalizeOnRead
import xyz.wagyourtail.unimined.util.LazyMutable
import xyz.wagyourtail.unimined.util.MavenCoords
import xyz.wagyourtail.unimined.util.getField
import java.io.File

/**
 * @since 1.0.0
 */
@Suppress("OVERLOADS_ABSTRACT", "UNUSED")
@Scoped
abstract class MappingsConfig<T: MappingsConfig<T>>(val project: Project, val minecraft: MinecraftConfig, subKey: String? = null) :
    MappingResolver<T>(buildString {
        append(project.path)
        append(minecraft.sourceSet.name)
        if (subKey != null) {
            append("-$subKey")
        }
    }) {

    private var innerDevNamespace: Namespace by FinalizeOnRead(LazyMutable {
        namespaces.entries.firstOrNull { it.value }?.key ?: error("No \"Named\" namespace found for devNamespace, if this is correct, set devNamespace explicitly")
    })

    @set:ApiStatus.Internal
    @get:ApiStatus.Internal
    var devNamespace: Namespace by FinalizeOnRead(LazyMutable {
        runBlocking {
            resolve()
        }
        innerDevNamespace
    })

    fun devNamespace(namespace: String) {
        val delegate = MappingsConfig::class.getField("innerDevNamespace")!!.getDelegate(this) as FinalizeOnRead<Namespace>
        delegate.setValueIntl(LazyMutable {
            checkedNs(namespace)
        })
    }

    @Deprecated("No longer needed", ReplaceWith(""))
    fun devFallbackNamespace(namespace: String) {}

    /**
     * @since 1.4
     */
    abstract var legacyFabricGenVersion: Int

    /**
     * @since 1.4
     */
    abstract var ornitheGenVersion: Int

    abstract fun intermediary()

    abstract fun calamus()

    abstract fun legacyIntermediary()

    abstract fun babricIntermediary()

    @JvmOverloads
    abstract fun searge(version: String = minecraft.version)

    abstract fun mojmap()

    abstract fun mcp(channel: String, version: String)

    @JvmOverloads
    abstract fun retroMCP(version: String = minecraft.version)

    @JvmOverloads
    abstract fun unknownThingy(version: String, format: String = "tsrg")

    abstract fun yarn(build: Int)

    fun yarn(build: String) {
        yarn(build.toInt())
    }

    abstract fun yarnv1(build: Int)

    fun yarnv1(build: String) {
        yarnv1(build.toInt())
    }

    abstract fun feather(build: Int)

    fun feather(build: String) {
        feather(build.toInt())
    }

    abstract fun legacyYarn(build: Int)

    fun legacyYarn(build: String) {
        legacyYarn(build.toInt())
    }

    abstract fun barn(build: Int)

    fun barn(build: String) {
        barn(build.toInt())
    }

    abstract fun biny(commitName: String)

    abstract fun nostalgia(build: Int)

    fun nostalgia(build: String) {
        nostalgia(build.toInt())
    }

    @JvmOverloads
    abstract fun quilt(build: Int)

    fun quilt(build: String) {
        quilt(build.toInt())
    }

    @JvmOverloads
    abstract fun forgeBuiltinMCP(version: String)

    @JvmOverloads
    abstract fun parchment(
        mcVersion: String = minecraft.version,
        version: String,
        checked: Boolean = false
    )

    @JvmOverloads
    abstract fun spigotDev(
        mcVersion: String = minecraft.version
    )

    @JvmOverloads
    @ApiStatus.Experimental
    abstract fun mapping(dependency: String, key: String = MavenCoords(dependency).artifact, action: @Scoped MappingResolver<T>.MappingEntry.() -> Unit = {})

    @JvmOverloads
    @ApiStatus.Experimental
    abstract fun mapping(dependency: MavenCoords, key: String = dependency.artifact, action: @Scoped MappingResolver<T>.MappingEntry.() -> Unit = {})

    @JvmOverloads
    @ApiStatus.Experimental
    abstract fun mapping(dependency: File, key: String = dependency.nameWithoutExtension, action: @Scoped MappingResolver<T>.MappingEntry.() -> Unit = {})

    @JvmOverloads
    @ApiStatus.Experimental
    fun mapping(
        dependency: String,
        key: String = MavenCoords(dependency).artifact,
        @DelegatesTo(value = MappingEntry::class, strategy = Closure.DELEGATE_FIRST)
        action: Closure<*>
    ) {
        mapping(dependency, key) {
            action.delegate = this
            action.resolveStrategy = Closure.DELEGATE_FIRST
            action.call()
        }
    }

    @JvmOverloads
    @ApiStatus.Experimental
    fun mapping(
        dependency: MavenCoords,
        key: String = dependency.artifact,
        @DelegatesTo(value = MappingEntry::class, strategy = Closure.DELEGATE_FIRST)
        action: Closure<*>
    ) {
        mapping(dependency, key) {
            action.delegate = this
            action.resolveStrategy = Closure.DELEGATE_FIRST
            action.call()
        }
    }

    @JvmOverloads
    @ApiStatus.Experimental
    fun mapping(
        dependency: File,
        key: String = dependency.nameWithoutExtension,
        @DelegatesTo(value = MappingEntry::class, strategy = Closure.DELEGATE_FIRST)
        action: Closure<*>
    ) {
        mapping(dependency, key) {
            action.delegate = this
            action.resolveStrategy = Closure.DELEGATE_FIRST
            action.call()
        }
    }

    abstract fun hasStubs(): Boolean

    @Deprecated("Use stubs instead", ReplaceWith("stubs(*namespaces, apply = apply)"))
    abstract val stub: MemoryMapping

    abstract fun stubs(vararg namespaces: String, apply: MappingDSL.() -> Unit)

    fun stubs(
        namespaces: List<String>,
        @DelegatesTo(value = MappingDSL::class, strategy = Closure.DELEGATE_FIRST)
        apply: Closure<*>
    ) {
        stubs(*namespaces.toTypedArray()) {
            apply.delegate = this
            apply.resolveStrategy = Closure.DELEGATE_FIRST
            apply.call()
        }
    }

    abstract fun configure(action: MappingsConfig<*>.() -> Unit)

    override suspend fun resolve(): MemoryMappingTree {
        return super.resolve()
    }

    @ApiStatus.Internal
    abstract suspend fun getTRMappings(
        remap: Pair<Namespace, Namespace>,
        remapLocals: Boolean = false
    ): (IMappingProvider.MappingAcceptor) -> Unit
}
