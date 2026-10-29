package xyz.wagyourtail.unimined

import groovy.lang.Closure
import groovy.lang.DelegatesTo
import org.gradle.api.Action
import org.gradle.api.Project
import org.gradle.api.provider.Property
import xyz.wagyourtail.unimined.minecraft.MinecraftExtension
import xyz.wagyourtail.unimined.mod.ModExtension
import xyz.wagyourtail.unimined.migrate.MigrateExtension
import xyz.wagyourtail.unimined.migrate.task.MigrateMappingsTask
import xyz.wagyourtail.unimined.remap.RemapExtension
import xyz.wagyourtail.unimined.transform.TransformExtension
import javax.inject.Inject

/**
 * The `unimined { }` facade — an aggregator presenting one DSL that fans
 * out to the underlying plugins' extensions. Each underlying plugin
 * remains independently applicable; this only folds them into one
 * interface. Transform-agnostic: transforms declare themselves through
 * the transform builder API on [TransformExtension].
 */
abstract class UniminedExtension @Inject constructor(private val project: Project) {

    /**
     * Global java-toolchain kill-switch — delegates to
     * [MinecraftExtension.useToolchains]. When false, tool execs,
     * compiles, and run launchers use the build JVM (which must
     * satisfy each environment's java requirement).
     */
    var useToolchains: Boolean
        get() = project.extensions.getByType(MinecraftExtension::class.java)
            .useToolchains.get()
        set(value) = project.extensions.getByType(MinecraftExtension::class.java)
            .useToolchains.set(value)

    /**
     * Default source-set java floor — delegates to
     * [MinecraftEnvironment.javaVersion] on the `main` environment
     * (`unimined { javaVersion = 21 }` to override the version-json /
     * loader-declared requirement).
     */
    val javaVersion: Property<Int>
        get() = project.extensions.getByType(MinecraftExtension::class.java)
            .main.javaVersion

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

    /**
     * `unimined.migrateMappings("main") { target { yarn(...) } }` —
     * registers the `migrateMappings<SS>` task migrating the source
     * set's sources onto a different mappings release — delegates to
     * the `unimined-migrate` plugin's `migrate` extension (see
     * `MigrateMappingsTask` / plan.md §6).
     */
    fun migrateMappings(environmentId: String, action: Action<MigrateMappingsTask>) {
        project.extensions.getByType(MigrateExtension::class.java)
            .migrateMappings(environmentId, action)
    }

    /** `unimined.migrate { }` → the `unimined-migrate` extension. */
    fun migrate(action: Action<MigrateExtension>) {
        action.execute(project.extensions.getByType(MigrateExtension::class.java))
    }

    /** Groovy DSL overload; delegates the closure to the migrate extension. */
    fun migrate(
        @DelegatesTo(value = MigrateExtension::class, strategy = Closure.DELEGATE_FIRST)
        action: Closure<*>
    ) {
        val extension = project.extensions.getByType(MigrateExtension::class.java)
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

    /** `unimined.mod { }` → the `unimined-mod` extension. */
    fun mod(action: Action<ModExtension>) {
        action.execute(project.extensions.getByType(ModExtension::class.java))
    }

    /** Groovy DSL overload; delegates the closure to the mod extension. */
    fun mod(
        @DelegatesTo(value = ModExtension::class, strategy = Closure.DELEGATE_FIRST)
        action: Closure<*>
    ) {
        val extension = project.extensions.getByType(ModExtension::class.java)
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
