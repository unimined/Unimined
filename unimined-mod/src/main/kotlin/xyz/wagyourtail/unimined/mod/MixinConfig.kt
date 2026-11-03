package xyz.wagyourtail.unimined.mod

import org.gradle.api.Named
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import java.io.Serializable
import javax.inject.Inject

/**
 * One generated mixin configuration — `mixins { create("examplemod") { } }`
 * emits `examplemod.mixins.json` alongside the loader metadata.
 */
abstract class MixinConfig @Inject constructor(
    private val name: String,
    objects: ObjectFactory
) : Named {

    override fun getName(): String = name

    /** Root package every relative mixin class name resolves under. */
    abstract val packageName: Property<String>

    /** Universal mixin classes. */
    abstract val mixins: ListProperty<String>

    /** Client-only mixin classes (fabric `client` array). */
    abstract val client: ListProperty<String>

    /** Server-only mixin classes (fabric `server` array). */
    abstract val server: ListProperty<String>

    /** Mixin compatibility level — e.g. `JAVA_17`; unset omits the key. */
    abstract val compatibilityLevel: Property<String>

    /** Minimum mixin version; convention `0.8`. */
    abstract val minVersion: Property<String>

    /** Default refmap name; convention `<name>-refmap.json`. */
    abstract val refmap: Property<String>

    /** Mixin plugin class, if any. */
    abstract val plugin: Property<String>

    init {
        minVersion.convention("0.8")
        refmap.convention("$name-refmap.json")
    }

    /** Add a universal mixin class (relative to [packageName]). */
    fun mixin(className: String) = mixins.add(className)

    /** Add a client-side mixin class. */
    fun client(className: String) = client.add(className)

    /** Add a server-side mixin class. */
    fun server(className: String) = server.add(className)

    /** Serializable snapshot for task inputs. */
    internal fun snapshot(): MixinConfigData = MixinConfigData(
        fileName = "$name.mixins.json",
        packageName = packageName.get(),
        mixins = mixins.get(),
        client = client.get(),
        server = server.get(),
        compatibilityLevel = compatibilityLevel.orNull,
        minVersion = minVersion.orNull,
        refmap = refmap.orNull,
        plugin = plugin.orNull
    )

}

/** Plain data form of [MixinConfig] — serialized into task inputs. */
data class MixinConfigData(
    val fileName: String,
    val packageName: String,
    val mixins: List<String>,
    val client: List<String>,
    val server: List<String>,
    val compatibilityLevel: String?,
    val minVersion: String?,
    val refmap: String?,
    val plugin: String?
) : Serializable
