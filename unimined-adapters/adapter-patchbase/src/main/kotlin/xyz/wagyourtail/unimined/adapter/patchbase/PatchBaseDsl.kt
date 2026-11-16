package xyz.wagyourtail.unimined.adapter.patchbase

import groovy.lang.Closure
import groovy.lang.DelegatesTo
import org.gradle.api.Action
import xyz.wagyourtail.unimined.adapter.patchbase.PatchBaseEnvExtension.PatchBaseCreatorSpec
import xyz.wagyourtail.unimined.api.util.delegatingTo
import xyz.wagyourtail.unimined.minecraft.MinecraftEnvironment
import xyz.wagyourtail.unimined.minecraft.MinecraftExtension

/**
 * Kotlin DSL accessor for the env's `patchBase` extension —
 * `sourceSet("main") { patchBase("my.patches:base-patch:1.0@zip") }`.
 * Groovy resolves the bare call through the env's `patchBase`
 * extension dynamically.
 */
fun MinecraftEnvironment.patchBase(notation: Any) =
    extensions.getByType(PatchBaseEnvExtension::class.java)
        .patchBase(notation)

/** `minecraft { patchBase(...) }` — [patchBase] on the `main` env. */
fun MinecraftExtension.patchBase(notation: Any) = main.patchBase(notation)

/**
 * Kotlin DSL accessor for the env's patch-authoring wiring —
 * `sourceSet("main") { patchBaseCreator() }` or with spec overrides:
 *
 * ```kotlin
 * sourceSet("main") {
 *     patchBaseCreator {
 *         patchDir.set(layout.projectDirectory.dir("mc-patches"))
 *         diffContextSize.set(5)
 *     }
 * }
 * ```
 */
fun MinecraftEnvironment.patchBaseCreator(
    action: Action<PatchBaseCreatorSpec> = Action {}
) = extensions.getByType(PatchBaseEnvExtension::class.java)
    .patchBaseCreator(action)

/** Groovy DSL form of [patchBaseCreator]. */
fun MinecraftEnvironment.patchBaseCreator(
    @DelegatesTo(value = PatchBaseCreatorSpec::class,
        strategy = Closure.DELEGATE_FIRST)
    action: Closure<*>
) = patchBaseCreator(Action { action.delegatingTo(it) })

/** `minecraft { patchBaseCreator() }` — [patchBaseCreator] on the
 *  `main` env. */
fun MinecraftExtension.patchBaseCreator(
    action: Action<PatchBaseCreatorSpec> = Action {}
) = main.patchBaseCreator(action)

/** Groovy DSL form of the extension-level [patchBaseCreator]. */
fun MinecraftExtension.patchBaseCreator(
    @DelegatesTo(value = PatchBaseCreatorSpec::class,
        strategy = Closure.DELEGATE_FIRST)
    action: Closure<*>
) = patchBaseCreator(Action { action.delegatingTo(it) })
