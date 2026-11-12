package xyz.wagyourtail.unimined.transform.jarmod

import xyz.wagyourtail.unimined.minecraft.MinecraftEnvironment
import xyz.wagyourtail.unimined.minecraft.MinecraftExtension

/**
 * Kotlin DSL sugar for the jarmod loader — thin shims delegating to
 * the per-env [JarModEnvExtension] (registered as the `jarmod`
 * extension on each environment when this plugin applies). Groovy
 * reaches the same methods through [ExtensionAware] dispatch; both
 * syntaxes share one implementation.
 */

private fun MinecraftEnvironment.jarmodExt(): JarModEnvExtension =
    extensions.getByType(JarModEnvExtension::class.java)

/**
 * Declare a jarmod environment — shorthand for `loader("jarmod", v)`
 * where [version] is the JarModAgent version. Merge inputs come from
 * the env's `jarMod<envId>` configuration (any dependency type).
 */
fun MinecraftEnvironment.jarmod(version: String) = jarmodExt().jarmod(version)

/**
 * Declare an FG1-era Forge environment — shorthand for
 * `loader("fg1", v)` where [version] is the JarModAgent version.
 * Forge itself is declared on the env's `forge<envId>` configuration
 * (e.g. `forge("net.minecraftforge:forge:1.4.7-6.6.2.534:universal@zip")`);
 * its bundled `forgeBuiltinMCP` mappings are auto-injected unless
 * [customSearge] is set.
 */
fun MinecraftEnvironment.fg1(version: String) = jarmodExt().fg1(version)

/**
 * 1.x `ForgeLikePatcher.customSearge` — the user supplies their own
 * searge/mcp mapping chain; disables the auto `forgeBuiltinMCP` hop
 * (pre-1.7 forge bundles searge inside the same src zip).
 */
fun MinecraftEnvironment.customSearge() = jarmodExt().customSearge()

/**
 * Opt-in: resolve `org.ow2.asm:asm-all:5.2` for FML's `asm-all` dyn
 * lib instead of the era's 4.x build — Java 8 classfile support in
 * dev (works wherever the dyn-lib mechanism exists, incl. 1.6.4).
 * The resolved jar is copied to `runDir/lib` under the filename
 * `CoreFMLLibraries` expects.
 */
fun MinecraftEnvironment.useAsm52() = jarmodExt().useAsm52()

/** Register a JarModAgent transformer class (applied at run time and,
 *  when compile-time transforms land, during remap). */
fun MinecraftEnvironment.jarmodTransformer(transform: String) =
    jarmodExt().jarmodTransformer(transform)

/** Declare a jarModAgent transformer set. */
fun MinecraftEnvironment.jarmodTransforms(transforms: List<String>) =
    jarmodExt().jarmodTransforms(transforms)

/** 1.x `jarModPatcher.deleteMetaInf` — strip the base jar's
 *  META-INF entirely during the merge (signature material is
 *  always dropped either way). */
fun MinecraftEnvironment.deleteMetaInf(disable: Boolean = true) =
    jarmodExt().deleteMetaInf(disable)

/** Register a refmap resource (`jma.refmaps`) — a classpath-resource
 *  name like `examplemod.refmap.json`; the agent merges all listed
 *  refmaps per transformer. */
fun MinecraftEnvironment.jarmodRefmap(refmap: String) =
    jarmodExt().jarmodRefmap(refmap)

/** Declare a set of refmap resources. */
fun MinecraftEnvironment.jarmodRefmaps(refmaps: List<String>) =
    jarmodExt().jarmodRefmaps(refmaps)

/** Register a `JMAInitializer` class (`jma.initializers`) — runs
 *  before manifest-declared initializers; call order is load order. */
fun MinecraftEnvironment.jarmodInitializer(initializer: String) =
    jarmodExt().jarmodInitializer(initializer)

/** Declare an ordered set of `JMAInitializer` classes. */
fun MinecraftEnvironment.jarmodInitializers(initializers: List<String>) =
    jarmodExt().jarmodInitializers(initializers)

/** `jma.failStrategy` — ClassTransform `FailStrategy` for transformer
 *  failures: CONTINUE, CANCEL, EXIT or THROW (agent ≥ 0.1.5). */
fun MinecraftEnvironment.jarmodFailStrategy(strategy: String) =
    jarmodExt().jarmodFailStrategy(strategy)

/** `jma.debug` — dump patched classes under `.jma/patched`. */
fun MinecraftEnvironment.jarmodDebug() = jarmodExt().jarmodDebug()

/** 1.x `jarModAgentPatcher.compiletimeTransforms` — run the declared
 *  jma transformers over this env's jar during `remapJar` instead of
 *  relying on the runtime `-javaagent` alone. */
fun MinecraftEnvironment.jarmodCompiletimeTransforms(disable: Boolean = true) =
    jarmodExt().jarmodCompiletimeTransforms(disable)

// `minecraft {}` configures the `main` environment — these forward
// there so `unimined { minecraft { jarmodTransformer("x.transform") } }`
// works alongside `sourceSet("other") { jarmodTransformer(...) }`.

fun MinecraftExtension.jarmodTransformer(transform: String) =
    environments.getByName("main").jarmodTransformer(transform)

fun MinecraftExtension.jarmodTransforms(transforms: List<String>) =
    environments.getByName("main").jarmodTransforms(transforms)

fun MinecraftExtension.jarmodRefmap(refmap: String) =
    environments.getByName("main").jarmodRefmap(refmap)

fun MinecraftExtension.jarmodRefmaps(refmaps: List<String>) =
    environments.getByName("main").jarmodRefmaps(refmaps)

fun MinecraftExtension.jarmodInitializer(initializer: String) =
    environments.getByName("main").jarmodInitializer(initializer)

fun MinecraftExtension.jarmodInitializers(initializers: List<String>) =
    environments.getByName("main").jarmodInitializers(initializers)

fun MinecraftExtension.jarmodFailStrategy(strategy: String) =
    environments.getByName("main").jarmodFailStrategy(strategy)

fun MinecraftExtension.jarmodDebug() =
    environments.getByName("main").jarmodDebug()

fun MinecraftExtension.jarmodCompiletimeTransforms(disable: Boolean = true) =
    environments.getByName("main").jarmodCompiletimeTransforms(disable)
