package xyz.wagyourtail.unimined.transform

import org.gradle.api.Action
import xyz.wagyourtail.unimined.minecraft.MinecraftEnvironment
import xyz.wagyourtail.unimined.minecraft.MinecraftExtension

/**
 * Kotlin DSL verb — `minecraft { loader("forge", "47.2.0") { spec ->
 * spec.nestedJars { } } }`: configures the loader plus this
 * environment's transform spec in one place, so packaging/feature
 * knobs colocate with the loader they belong to. Lives here (not on
 * the env) because `MinecraftEnvironment` cannot see
 * `TransformEnvironmentSpec` — Groovy DSL gets the equivalent via the
 * env's `loader(String, String, Closure)` member.
 */
fun MinecraftEnvironment.loader(
    name: String,
    version: String,
    action: Action<in TransformEnvironmentSpec>
) {
    loader(name, version)
    action.execute(transformSpec() as TransformEnvironmentSpec)
}

/** Single-arg form — `loader("foxloader") { spec -> ... }`. */
fun MinecraftEnvironment.loader(
    name: String,
    action: Action<in TransformEnvironmentSpec>
) {
    loader(name)
    action.execute(transformSpec() as TransformEnvironmentSpec)
}

/**
 * Kotlin DSL verb — `minecraft { transform { spec -> ... } }`:
 * configures this environment's transform spec in place. Groovy
 * needs no equivalent — the transform wiring attaches the spec as
 * the named `transform` extension on the env (envs are
 * `ExtensionAware`), so `transform { }` delegates to it natively.
 */
fun MinecraftEnvironment.transform(
    action: Action<in TransformEnvironmentSpec>
) {
    action.execute(transformSpec() as TransformEnvironmentSpec)
}

/** `minecraft { loader("forge", "v") { } }` — main-env facade. */
fun MinecraftExtension.loader(
    name: String,
    version: String,
    action: Action<in TransformEnvironmentSpec>
) = main.loader(name, version, action)

/** `minecraft { loader("foxloader") { } }` — main-env facade. */
fun MinecraftExtension.loader(
    name: String,
    action: Action<in TransformEnvironmentSpec>
) = main.loader(name, action)

/** `minecraft { transform { } }` — main-env facade. */
fun MinecraftExtension.transform(
    action: Action<in TransformEnvironmentSpec>
) = main.transform(action)
