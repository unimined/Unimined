package xyz.wagyourtail.unimined.mod

import groovy.lang.Closure
import groovy.lang.DelegatesTo
import org.gradle.api.Action
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.Project
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import javax.inject.Inject

/**
 * The `mod { }` extension (`unimined.mod { }` on the frontend) —
 * loader-neutral mod identity plus per-loader sections and generated mixin
 * configs. Loader emitters turn the model into `fabric.mod.json`,
 * `mods.toml`, etc. via the [ModMetadataEmitterRegistry] SPI.
 *
 * Example:
 * ```
 * mod {
 *     id = "examplemod"
 *     mixins { create("examplemod") { packageName = "example.mixin" } }
 *     loader("fabric") {
 *         entrypoint("main", "example.ExampleMod")
 *         accessWidener("examplemod.accesswidener")
 *     }
 * }
 * ```
 */
abstract class ModExtension @Inject constructor(
    project: Project,
    objects: org.gradle.api.model.ObjectFactory
) {

    /** Mod id — required for metadata emission; lowercase `a-z0-9_-`. */
    abstract val id: Property<String>

    /** Display name; defaults to [id] at emission. */
    abstract val name: Property<String>

    /** Mod version; convention `project.version`. */
    abstract val version: Property<String>

    /** Human-readable description. */
    abstract val description: Property<String>

    /** License id(s) — e.g. `"MIT"`. */
    abstract val license: Property<String>

    /** Homepage URL. */
    abstract val url: Property<String>

    /** Icon resource path. */
    abstract val icon: Property<String>

    /** Loader-neutral environment (`*`, `client`, `server`). */
    abstract val environment: Property<String>

    /** Author names. */
    abstract val authors: ListProperty<String>

    /** `modid -> version range` dependency declarations. */
    abstract val depends: MapProperty<String, String>

    /** Generated mixin configs — `mixins { create("name") { } }` emits
     *  `name.mixins.json` into the environment's generated resources. */
    val mixins: NamedDomainObjectContainer<MixinConfig> =
        objects.domainObjectContainer(MixinConfig::class.java)

    private val loaderSections = linkedMapOf<String, LoaderSection>()

    init {
        version.convention(project.provider { project.version.toString() })
    }

    /** Add an author. */
    fun author(vararg names: String) = authors.addAll(names.toList())

    /** Declare `depends` on mod [modId] at [versionRange]. */
    fun depend(modId: String, versionRange: String) = depends.put(modId, versionRange)

    /** Configure generated mixin configs. */
    fun mixins(action: Action<NamedDomainObjectContainer<MixinConfig>>) {
        action.execute(mixins)
    }

    /** Groovy DSL overload for [mixins]. */
    fun mixins(
        @DelegatesTo(
            value = NamedDomainObjectContainer::class,
            strategy = Closure.DELEGATE_FIRST
        )
        action: Closure<*>
    ) {
        action.delegate = mixins
        action.resolveStrategy = Closure.DELEGATE_FIRST
        action.call()
    }

    /**
     * Declare loader-specific metadata — `loader("fabric") { }` creates the
     * registered emitter's section (a [LoaderSection]) and configures it.
     * Emitters are discovered per environment at task execution; the
     * section object itself is captured as a serializable task input.
     */
    fun loader(name: String, action: Action<LoaderSection>) {
        val section = loaderSections.getOrPut(name) {
            ModMetadataEmitterRegistry().select(name).createSection()
        }
        action.execute(section)
    }

    /** Groovy DSL overload for [loader]. */
    fun loader(
        name: String,
        @DelegatesTo(value = LoaderSection::class, strategy = Closure.DELEGATE_FIRST)
        action: Closure<*>
    ) {
        loader(name, Action<LoaderSection> { section ->
            action.delegate = section
            action.resolveStrategy = Closure.DELEGATE_FIRST
            action.call()
        })
    }

    /** `fabric { }` — convenience for `loader("fabric") { }`, the bundled
     *  reference loader. Other loaders use `loader("<name>")`. */
    fun fabric(action: Action<LoaderSection>) = loader("fabric", action)

    /** Groovy DSL overload for [fabric]. */
    fun fabric(
        @DelegatesTo(value = LoaderSection::class, strategy = Closure.DELEGATE_FIRST)
        action: Closure<*>
    ) = loader("fabric", action)

    /** Serializable model snapshot for task inputs. */
    internal fun snapshot(): ModMetadata = ModMetadata(
        id = id.orNull,
        name = name.orNull,
        version = version.orNull,
        description = description.orNull,
        authors = authors.get(),
        license = license.orNull,
        url = url.orNull,
        icon = icon.orNull,
        environment = environment.orNull,
        depends = depends.get(),
        sections = loaderSections.toMap()
    )

}
