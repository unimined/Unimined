package xyz.wagyourtail.unimined.api.mapping

import MemoryMapping
import groovy.lang.Closure
import groovy.lang.DelegatesTo
import kotlinx.coroutines.runBlocking
import net.fabricmc.tinyremapper.IMappingProvider
import org.gradle.api.Project
import org.jetbrains.annotations.ApiStatus
import xyz.wagyourtail.unimined.api.mapping.dsl.MappingDSL
import xyz.wagyourtail.unimined.api.minecraft.MinecraftConfig
import xyz.wagyourtail.unimined.mapping.Namespace
import xyz.wagyourtail.unimined.mapping.resolver.MappingResolver
import xyz.wagyourtail.unimined.mapping.tree.MemoryMappingTree
import xyz.wagyourtail.unimined.util.FinalizeOnRead
import xyz.wagyourtail.unimined.util.LazyMutable
import xyz.wagyourtail.unimined.util.MavenCoords
import xyz.wagyourtail.unimined.util.getField
import java.io.File

/**
 * @since 1.0.0
 */
@Suppress("OVERLOADS_ABSTRACT", "UNUSED")
abstract class MappingsConfig<T: MappingResolver<T>>(val project: Project, val minecraft: MinecraftConfig, subKey: String? = null) :
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

    @JvmOverloads
    abstract fun intermediary(key: String = "intermediary", action: MappingEntry.() -> Unit = {})

    @JvmOverloads
    fun intermediary(
        key: String = "intermediary",
        @DelegatesTo(value = MappingEntry::class, strategy = Closure.DELEGATE_FIRST)
        action: Closure<*>
    ) {
        intermediary(key) {
            action.delegate = this
            action.resolveStrategy = Closure.DELEGATE_FIRST
            action.call()
        }
    }

    @JvmOverloads
    abstract fun calamus(key: String = "calamus", action: MappingEntry.() -> Unit = {})

    @JvmOverloads
    fun calamus(
        key: String = "calamus",
        @DelegatesTo(value = MappingEntry::class, strategy = Closure.DELEGATE_FIRST)
        action: Closure<*>
    ) {
        calamus(key) {
            action.delegate = this
            action.resolveStrategy = Closure.DELEGATE_FIRST
            action.call()
        }
    }

    @JvmOverloads
    abstract fun legacyIntermediary(key: String = "legacyIntermediary", action: MappingEntry.() -> Unit = {})

    @JvmOverloads
    fun legacyIntermediary(
        key: String = "legacyIntermediary",
        @DelegatesTo(value = MappingEntry::class, strategy = Closure.DELEGATE_FIRST)
        action: Closure<*>
    ) {
        legacyIntermediary(key) {
            action.delegate = this
            action.resolveStrategy = Closure.DELEGATE_FIRST
            action.call()
        }
    }

    @JvmOverloads
    abstract fun babricIntermediary(key: String = "babricIntermediary", action: MappingEntry.() -> Unit = {})

    @JvmOverloads
    fun babricIntermediary(
        key: String = "babricIntermediary",
        @DelegatesTo(value = MappingEntry::class, strategy = Closure.DELEGATE_FIRST)
        action: Closure<*>
    ) {
        babricIntermediary(key) {
            action.delegate = this
            action.resolveStrategy = Closure.DELEGATE_FIRST
            action.call()
        }
    }

    @JvmOverloads
    abstract fun searge(version: String = minecraft.version, key: String = "searge", action: MappingEntry.() -> Unit = {})

    @JvmOverloads
    fun searge(
        version: String = minecraft.version,
        key: String = "searge",
        @DelegatesTo(value = MappingEntry::class, strategy = Closure.DELEGATE_FIRST)
        action: Closure<*>
    ) {
        searge(version, key) {
            action.delegate = this
            action.resolveStrategy = Closure.DELEGATE_FIRST
            action.call()
        }
    }

    @JvmOverloads
    abstract fun mojmap(key: String = "mojmap", action: MappingEntry.() -> Unit = {})

    @JvmOverloads
    fun mojmap(
        key: String = "mojmap",
        @DelegatesTo(value = MappingEntry::class, strategy = Closure.DELEGATE_FIRST)
        action: Closure<*>
    ) {
        mojmap(key) {
            action.delegate = this
            action.resolveStrategy = Closure.DELEGATE_FIRST
            action.call()
        }
    }

    @JvmOverloads
    abstract fun mcp(channel: String, version: String, key: String = "mcp", action: MappingEntry.() -> Unit = {})

    @JvmOverloads
    fun mcp(
        channel: String,
        version: String,
        key: String = "mcp",
        @DelegatesTo(value = MappingEntry::class, strategy = Closure.DELEGATE_FIRST)
        action: Closure<*>
    ) {
        mcp(channel, version, key) {
            action.delegate = this
            action.resolveStrategy = Closure.DELEGATE_FIRST
            action.call()
        }
    }

    @JvmOverloads
    abstract fun retroMCP(version: String = minecraft.version, key: String = "retroMCP", action: MappingEntry.() -> Unit = {})

    @JvmOverloads
    fun retroMCP(
        version: String = minecraft.version,
        key: String = "retroMCP",
        @DelegatesTo(value = MappingEntry::class, strategy = Closure.DELEGATE_FIRST)
        action: Closure<*>
    ) {
        retroMCP(version, key) {
            action.delegate = this
            action.resolveStrategy = Closure.DELEGATE_FIRST
            action.call()
        }
    }

    @JvmOverloads
    abstract fun unknownThingy(version: String, format: String = "tsrg", key: String = "unknownThingy", action: MappingEntry.() -> Unit = {})

    @JvmOverloads
    fun unknownThingy(
        version: String = minecraft.version,
        key: String = "unknownThingy",
        format: String = "tsrg",
        @DelegatesTo(value = MappingEntry::class, strategy = Closure.DELEGATE_FIRST)
        action: Closure<*>
    ) {
        unknownThingy(version, format, key) {
            action.delegate = this
            action.resolveStrategy = Closure.DELEGATE_FIRST
            action.call()
        }
    }

    @JvmOverloads
    abstract fun yarn(build: Int, key: String = "yarn", action: MappingEntry.() -> Unit = {})

    @JvmOverloads
    fun yarn(build: String, key: String = "yarn", action: MappingEntry.() -> Unit = {}) {
        yarn(build.toInt(), key, action)
    }

    @JvmOverloads
    fun yarn(
        build: Int,
        key: String = "yarn",
        @DelegatesTo(value = MappingEntry::class, strategy = Closure.DELEGATE_FIRST)
        action: Closure<*>
    ) {
        yarn(build, key) {
            action.delegate = this
            action.resolveStrategy = Closure.DELEGATE_FIRST
            action.call()
        }
    }

    @JvmOverloads
    fun yarn(
        build: String,
        key: String = "yarn",
        @DelegatesTo(value = MappingEntry::class, strategy = Closure.DELEGATE_FIRST)
        action: Closure<*>
    ) {
        yarn(build.toInt(), key, action)
    }



    @JvmOverloads
    abstract fun yarnv1(build: Int, key: String = "yarnv1", action: MappingEntry.() -> Unit = {})

    @JvmOverloads
    fun yarnv1(build: String, key: String = "yarnv1", action: MappingEntry.() -> Unit = {}) {
        yarnv1(build.toInt(), key, action)
    }

    @JvmOverloads
    fun yarnv1(
        build: Int,
        key: String = "yarnv1",
        @DelegatesTo(value = MappingEntry::class, strategy = Closure.DELEGATE_FIRST)
        action: Closure<*>
    ) {
        yarnv1(build, key) {
            action.delegate = this
            action.resolveStrategy = Closure.DELEGATE_FIRST
            action.call()
        }
    }

    @JvmOverloads
    fun yarnv1(
        build: String,
        key: String = "yarnv1",
        @DelegatesTo(value = MappingEntry::class, strategy = Closure.DELEGATE_FIRST)
        action: Closure<*>
    ) {
        yarn(build.toInt(), key, action)
    }

    @JvmOverloads
    abstract fun feather(build: Int, key: String = "feather", action: MappingEntry.() -> Unit = {})

    @JvmOverloads
    fun feather(build: String, key: String = "feather", action: MappingEntry.() -> Unit = {}) {
        feather(build.toInt(), key, action)
    }

    @JvmOverloads
    fun feather(
        build: Int,
        key: String = "feather",
        @DelegatesTo(value = MappingEntry::class, strategy = Closure.DELEGATE_FIRST)
        action: Closure<*>
    ) {
        feather(build, key) {
            action.delegate = this
            action.resolveStrategy = Closure.DELEGATE_FIRST
            action.call()
        }
    }

    @JvmOverloads
    fun feather(
        build: String,
        key: String = "feather",
        @DelegatesTo(value = MappingEntry::class, strategy = Closure.DELEGATE_FIRST)
        action: Closure<*>
    ) {
        feather(build.toInt(), key, action)
    }

    @JvmOverloads
    abstract fun legacyYarn(build: Int, key: String = "legacyYarn", action: MappingEntry.() -> Unit = {})

    @JvmOverloads
    fun legacyYarn(build: String, key: String = "legacyYarn", action: MappingEntry.() -> Unit = {}) {
        legacyYarn(build.toInt(), key, action)
    }

    @JvmOverloads
    fun legacyYarn(
        build: Int,
        key: String = "legacyYarn",
        @DelegatesTo(value = MappingEntry::class, strategy = Closure.DELEGATE_FIRST)
        action: Closure<*>
    ) {
        legacyYarn(build, key) {
            action.delegate = this
            action.resolveStrategy = Closure.DELEGATE_FIRST
            action.call()
        }
    }

    @JvmOverloads
    abstract fun plasma(commitName: String, key: String = "plasma", action: MappingEntry.() -> Unit = {})

    @JvmOverloads
    fun plasma(
        commitName: String,
        key: String = "plasma",
        @DelegatesTo(value = MappingEntry::class, strategy = Closure.DELEGATE_FIRST)
        action: Closure<*>
    ) {
        biny(commitName, key) {
            action.delegate = this
            action.resolveStrategy = Closure.DELEGATE_FIRST
            action.call()
        }
    }

    @JvmOverloads
    abstract fun barn(build: Int, key: String = "barn", action: MappingEntry.() -> Unit = {})

    @JvmOverloads
    fun barn(build: String, key: String = "barn", action: MappingEntry.() -> Unit = {}) {
        barn(build.toInt(), key, action)
    }

    @JvmOverloads
    fun barn(
        build: Int,
        key: String = "barn",
        @DelegatesTo(value = MappingEntry::class, strategy = Closure.DELEGATE_FIRST)
        action: Closure<*>
    ) {
        barn(build, key) {
            action.delegate = this
            action.resolveStrategy = Closure.DELEGATE_FIRST
            action.call()
        }
    }

    @JvmOverloads
    fun barn(
        build: String,
        key: String = "barn",
        @DelegatesTo(value = MappingEntry::class, strategy = Closure.DELEGATE_FIRST)
        action: Closure<*>
    ) {
        barn(build.toInt(), key, action)
    }

    @JvmOverloads
    abstract fun biny(commitName: String, key: String = "biny", action: MappingEntry.() -> Unit = {})

    @JvmOverloads
    fun biny(
        commitName: String,
        key: String = "biny",
        @DelegatesTo(value = MappingEntry::class, strategy = Closure.DELEGATE_FIRST)
        action: Closure<*>
    ) {
        biny(commitName, key) {
            action.delegate = this
            action.resolveStrategy = Closure.DELEGATE_FIRST
            action.call()
        }
    }

    @JvmOverloads
    fun nostalgia(
        build: String,
        key: String = "nostalgia",
        @DelegatesTo(value = MappingEntry::class, strategy = Closure.DELEGATE_FIRST)
        action: Closure<*>
    ) {
        nostalgia(build.toInt(), key, action)
    }

    @JvmOverloads
    abstract fun nostalgia(build: Int, key: String = "nostalgia", action: MappingEntry.() -> Unit = {})

    @JvmOverloads
    fun nostalgia(build: String, key: String = "nostalgia", action: MappingEntry.() -> Unit = {}) {
        nostalgia(build.toInt(), key, action)
    }

    @JvmOverloads
    fun nostalgia(
        build: Int,
        key: String = "nostalgia",
        @DelegatesTo(value = MappingEntry::class, strategy = Closure.DELEGATE_FIRST)
        action: Closure<*>
    ) {
        nostalgia(build, key) {
            action.delegate = this
            action.resolveStrategy = Closure.DELEGATE_FIRST
            action.call()
        }
    }


    @JvmOverloads
    abstract fun quilt(build: Int, key: String = "quilt", action: MappingEntry.() -> Unit = {})

    @JvmOverloads
    fun quilt(build: String, key: String = "quilt", action: MappingEntry.() -> Unit = {}) {
        quilt(build.toInt(), key, action)
    }

    @JvmOverloads
    fun quilt(
        build: Int,
        key: String = "quilt",
        @DelegatesTo(value = MappingEntry::class, strategy = Closure.DELEGATE_FIRST)
        action: Closure<*>
    ) {
        quilt(build, key) {
            action.delegate = this
            action.resolveStrategy = Closure.DELEGATE_FIRST
            action.call()
        }
    }

    @JvmOverloads
    fun quilt(
        build: String,
        key: String = "quilt",
        @DelegatesTo(value = MappingEntry::class, strategy = Closure.DELEGATE_FIRST)
        action: Closure<*>
    ) {
        quilt(build.toInt(), key, action)
    }

    @JvmOverloads
    abstract fun forgeBuiltinMCP(version: String, key: String = "forgeBuiltinMCP", action: MappingEntry.() -> Unit = {})

    @JvmOverloads
    fun forgeBuiltinMCP(
        version: String,
        key: String = "forgeBuiltinMCP",
        @DelegatesTo(value = MappingEntry::class, strategy = Closure.DELEGATE_FIRST)
        action: Closure<*>
    ) {
        forgeBuiltinMCP(version, key) {
            action.delegate = this
            action.resolveStrategy = Closure.DELEGATE_FIRST
            action.call()
        }
    }

    @JvmOverloads
    abstract fun parchment(
        mcVersion: String = minecraft.version,
        version: String,
        checked: Boolean = false,
        key: String = "parchment",
        action: MappingEntry.() -> Unit = {}
    )

    @JvmOverloads
    fun parchment(
        mcVersion: String = minecraft.version,
        version: String,
        checked: Boolean = false,
        key: String = "parchment",
        @DelegatesTo(value = MappingEntry::class, strategy = Closure.DELEGATE_FIRST)
        action: Closure<*>
    ) {
        parchment(mcVersion, version, checked, key) {
            action.delegate = this
            action.resolveStrategy = Closure.DELEGATE_FIRST
            action.call()
        }
    }

    @JvmOverloads
    abstract fun spigotDev(
        mcVersion: String = minecraft.version,
        key: String = "spigotDev",
        action: MappingEntry.() -> Unit = {}
    )

    @JvmOverloads
    fun spigotDev(
        mcVersion: String = minecraft.version,
        key: String = "spigotDev",
        @DelegatesTo(value = MappingEntry::class, strategy = Closure.DELEGATE_FIRST)
        action: Closure<*>
    ) {
        spigotDev(mcVersion, key) {
            action.delegate = this
            action.resolveStrategy = Closure.DELEGATE_FIRST
            action.call()
        }
    }

    @JvmOverloads
    @ApiStatus.Experimental
    abstract fun mapping(dependency: String, key: String = MavenCoords(dependency).artifact, action: MappingEntry.() -> Unit = {})

    @JvmOverloads
    @ApiStatus.Experimental
    abstract fun mapping(dependency: File, key: String = dependency.nameWithoutExtension, action: MappingEntry.() -> Unit = {})

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
