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

// region named-verb spec-config twins — Kotlin `verb("version") { spec
// -> ... }` for every live loader verb; members on
// MinecraftEnvironment/MinecraftExtension cover `verb(version)` and
// Groovy's `verb "v", { }`. Same contract as
// `loader(name, version, Action)`: declares the loader, then applies
// the action to this environment's transform spec.

private inline fun MinecraftEnvironment.specVerb(
    declare: () -> Unit,
    action: Action<in TransformEnvironmentSpec>
) {
    declare()
    action.execute(transformSpec() as TransformEnvironmentSpec)
}

/** `fabric("0.16.0") { spec -> ... }`. */
fun MinecraftEnvironment.fabric(
    version: String,
    action: Action<in TransformEnvironmentSpec>
) = specVerb({ fabric(version) }, action)

/** `quilt("0.26.0") { spec -> ... }`. */
fun MinecraftEnvironment.quilt(
    version: String,
    action: Action<in TransformEnvironmentSpec>
) = specVerb({ quilt(version) }, action)

/** `forge("47.2.0") { spec -> ... }`. */
fun MinecraftEnvironment.forge(
    version: String,
    action: Action<in TransformEnvironmentSpec>
) = specVerb({ forge(version) }, action)

/** `lexforge("47.1.0") { spec -> ... }`. */
fun MinecraftEnvironment.lexforge(
    version: String,
    action: Action<in TransformEnvironmentSpec>
) = specVerb({ lexforge(version) }, action)

/** `neoforge("21.0.0") { spec -> ... }`. */
fun MinecraftEnvironment.neoforge(
    version: String,
    action: Action<in TransformEnvironmentSpec>
) = specVerb({ neoforge(version) }, action)

/** 1.x spelling — `neoForge("21.0.0") { spec -> ... }`. */
fun MinecraftEnvironment.neoForge(
    version: String,
    action: Action<in TransformEnvironmentSpec>
) = specVerb({ neoForge(version) }, action)

/** 1.x spelling — `neoForged("21.0.0") { spec -> ... }`. */
fun MinecraftEnvironment.neoForged(
    version: String,
    action: Action<in TransformEnvironmentSpec>
) = specVerb({ neoForged(version) }, action)

/** 1.x spelling — `minecraftForge("47.2.0") { spec -> ... }`. */
fun MinecraftEnvironment.minecraftForge(
    version: String,
    action: Action<in TransformEnvironmentSpec>
) = specVerb({ minecraftForge(version) }, action)

/** `cleanroom("0.4.0") { spec -> ... }`. */
fun MinecraftEnvironment.cleanroom(
    version: String,
    action: Action<in TransformEnvironmentSpec>
) = specVerb({ cleanroom(version) }, action)

/** `legacyfabric("0.16.0") { spec -> ... }`. */
fun MinecraftEnvironment.legacyfabric(
    version: String,
    action: Action<in TransformEnvironmentSpec>
) = specVerb({ legacyfabric(version) }, action)

/** 1.x spelling — `legacyFabric("0.16.0") { spec -> ... }`. */
fun MinecraftEnvironment.legacyFabric(
    version: String,
    action: Action<in TransformEnvironmentSpec>
) = specVerb({ legacyFabric(version) }, action)

/** `babric("0.16.0") { spec -> ... }`. */
fun MinecraftEnvironment.babric(
    version: String,
    action: Action<in TransformEnvironmentSpec>
) = specVerb({ babric(version) }, action)

/** `ornithe("0.19.5") { spec -> ... }`. */
fun MinecraftEnvironment.ornithe(
    version: String,
    action: Action<in TransformEnvironmentSpec>
) = specVerb({ ornithe(version) }, action)

/** 1.x spelling — `ornitheFabric("0.19.5") { spec -> ... }`. */
fun MinecraftEnvironment.ornitheFabric(
    version: String,
    action: Action<in TransformEnvironmentSpec>
) = specVerb({ ornitheFabric(version) }, action)

/** `jarmod("0.1.5") { spec -> ... }`. */
fun MinecraftEnvironment.jarmod(
    version: String,
    action: Action<in TransformEnvironmentSpec>
) = specVerb({ jarmod(version) }, action)

/** 1.x spelling — `jarMod("0.1.5") { spec -> ... }`. */
fun MinecraftEnvironment.jarMod(
    version: String,
    action: Action<in TransformEnvironmentSpec>
) = specVerb({ jarMod(version) }, action)

/** `fg1("1.0.0") { spec -> ... }`. */
fun MinecraftEnvironment.fg1(
    version: String,
    action: Action<in TransformEnvironmentSpec>
) = specVerb({ fg1(version) }, action)

/** `foxloader("2.0") { spec -> ... }`. */
fun MinecraftEnvironment.foxloader(
    version: String,
    action: Action<in TransformEnvironmentSpec>
) = specVerb({ foxloader(version) }, action)

/** Version-omitting form — `foxloader { spec -> ... }`. */
fun MinecraftEnvironment.foxloader(
    action: Action<in TransformEnvironmentSpec>
) = specVerb({ foxloader() }, action)

/** 1.x spelling — `foxLoader("2.0") { spec -> ... }`. */
fun MinecraftEnvironment.foxLoader(
    version: String,
    action: Action<in TransformEnvironmentSpec>
) = specVerb({ foxLoader(version) }, action)

/** 1.x spelling, version-omitting — `foxLoader { spec -> ... }`. */
fun MinecraftEnvironment.foxLoader(
    action: Action<in TransformEnvironmentSpec>
) = specVerb({ foxLoader() }, action)

/** `liteloader("1.12.2") { spec -> ... }`. */
fun MinecraftEnvironment.liteloader(
    version: String,
    action: Action<in TransformEnvironmentSpec>
) = specVerb({ liteloader(version) }, action)

/** `rift("1.0.4") { spec -> ... }`. */
fun MinecraftEnvironment.rift(
    version: String,
    action: Action<in TransformEnvironmentSpec>
) = specVerb({ rift(version) }, action)

/** `spigot("1.20.1") { spec -> ... }`. */
fun MinecraftEnvironment.spigot(
    version: String,
    action: Action<in TransformEnvironmentSpec>
) = specVerb({ spigot(version) }, action)

/** `paper("1.20.1") { spec -> ... }`. */
fun MinecraftEnvironment.paper(
    version: String,
    action: Action<in TransformEnvironmentSpec>
) = specVerb({ paper(version) }, action)

/** `craftbukkit("1.20.1") { spec -> ... }`. */
fun MinecraftEnvironment.craftbukkit(
    version: String,
    action: Action<in TransformEnvironmentSpec>
) = specVerb({ craftbukkit(version) }, action)

/** 1.x spelling — `craftBukkit("1.20.1") { spec -> ... }`. */
fun MinecraftEnvironment.craftBukkit(
    version: String,
    action: Action<in TransformEnvironmentSpec>
) = specVerb({ craftBukkit(version) }, action)

/** Family-name shorthand — `bukkit("1.20.1") { spec -> ... }`. */
fun MinecraftEnvironment.bukkit(
    version: String,
    action: Action<in TransformEnvironmentSpec>
) = specVerb({ bukkit(version) }, action)

// ---- `minecraft { verb("v") { } }` main-env facades ----

fun MinecraftExtension.fabric(
    version: String, action: Action<in TransformEnvironmentSpec>
) = main.fabric(version, action)

fun MinecraftExtension.quilt(
    version: String, action: Action<in TransformEnvironmentSpec>
) = main.quilt(version, action)

fun MinecraftExtension.forge(
    version: String, action: Action<in TransformEnvironmentSpec>
) = main.forge(version, action)

fun MinecraftExtension.lexforge(
    version: String, action: Action<in TransformEnvironmentSpec>
) = main.lexforge(version, action)

fun MinecraftExtension.neoforge(
    version: String, action: Action<in TransformEnvironmentSpec>
) = main.neoforge(version, action)

fun MinecraftExtension.neoForge(
    version: String, action: Action<in TransformEnvironmentSpec>
) = main.neoForge(version, action)

fun MinecraftExtension.neoForged(
    version: String, action: Action<in TransformEnvironmentSpec>
) = main.neoForged(version, action)

fun MinecraftExtension.minecraftForge(
    version: String, action: Action<in TransformEnvironmentSpec>
) = main.minecraftForge(version, action)

fun MinecraftExtension.cleanroom(
    version: String, action: Action<in TransformEnvironmentSpec>
) = main.cleanroom(version, action)

fun MinecraftExtension.legacyfabric(
    version: String, action: Action<in TransformEnvironmentSpec>
) = main.legacyfabric(version, action)

fun MinecraftExtension.legacyFabric(
    version: String, action: Action<in TransformEnvironmentSpec>
) = main.legacyFabric(version, action)

fun MinecraftExtension.babric(
    version: String, action: Action<in TransformEnvironmentSpec>
) = main.babric(version, action)

fun MinecraftExtension.ornithe(
    version: String, action: Action<in TransformEnvironmentSpec>
) = main.ornithe(version, action)

fun MinecraftExtension.ornitheFabric(
    version: String, action: Action<in TransformEnvironmentSpec>
) = main.ornitheFabric(version, action)

fun MinecraftExtension.jarmod(
    version: String, action: Action<in TransformEnvironmentSpec>
) = main.jarmod(version, action)

fun MinecraftExtension.jarMod(
    version: String, action: Action<in TransformEnvironmentSpec>
) = main.jarMod(version, action)

fun MinecraftExtension.fg1(
    version: String, action: Action<in TransformEnvironmentSpec>
) = main.fg1(version, action)

fun MinecraftExtension.foxloader(
    version: String, action: Action<in TransformEnvironmentSpec>
) = main.foxloader(version, action)

fun MinecraftExtension.foxloader(
    action: Action<in TransformEnvironmentSpec>
) = main.foxloader(action)

fun MinecraftExtension.foxLoader(
    version: String, action: Action<in TransformEnvironmentSpec>
) = main.foxLoader(version, action)

fun MinecraftExtension.foxLoader(
    action: Action<in TransformEnvironmentSpec>
) = main.foxLoader(action)

fun MinecraftExtension.liteloader(
    version: String, action: Action<in TransformEnvironmentSpec>
) = main.liteloader(version, action)

fun MinecraftExtension.rift(
    version: String, action: Action<in TransformEnvironmentSpec>
) = main.rift(version, action)

fun MinecraftExtension.spigot(
    version: String, action: Action<in TransformEnvironmentSpec>
) = main.spigot(version, action)

fun MinecraftExtension.paper(
    version: String, action: Action<in TransformEnvironmentSpec>
) = main.paper(version, action)

fun MinecraftExtension.craftbukkit(
    version: String, action: Action<in TransformEnvironmentSpec>
) = main.craftbukkit(version, action)

fun MinecraftExtension.craftBukkit(
    version: String, action: Action<in TransformEnvironmentSpec>
) = main.craftBukkit(version, action)

fun MinecraftExtension.bukkit(
    version: String, action: Action<in TransformEnvironmentSpec>
) = main.bukkit(version, action)

// endregion
