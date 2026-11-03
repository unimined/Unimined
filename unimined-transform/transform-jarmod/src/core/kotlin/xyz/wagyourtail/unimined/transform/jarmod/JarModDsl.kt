package xyz.wagyourtail.unimined.transform.jarmod

import xyz.wagyourtail.unimined.minecraft.MinecraftEnvironment

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
