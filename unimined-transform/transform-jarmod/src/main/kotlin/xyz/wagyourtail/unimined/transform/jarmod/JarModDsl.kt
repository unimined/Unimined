package xyz.wagyourtail.unimined.transform.jarmod

import xyz.wagyourtail.unimined.transform.jarmod.internal.JarModEnvState
import xyz.wagyourtail.unimined.minecraft.MinecraftEnvironment

/**
 * Declare a jarmod environment — shorthand for `loader("jarmod", v)`
 * where [version] is the JarModAgent version. Merge inputs come from
 * the env's `jarMod<envId>` configuration (any dependency type).
 */
fun MinecraftEnvironment.jarmod(version: String) {
    loader("jarmod", version)
}

/**
 * Declare an FG1-era Forge environment — shorthand for
 * `loader("fg1", v)` where [version] is the JarModAgent version.
 * Forge itself is declared on the env's `forge<envId>` configuration
 * (e.g. `forge("net.minecraftforge:forge:1.4.7-6.6.2.534:universal@zip")`);
 * its bundled `forgeBuiltinMCP` mappings are auto-injected unless
 * [customSearge] is set.
 */
fun MinecraftEnvironment.fg1(version: String) {
    loader("fg1", version)
}

/**
 * 1.x `ForgeLikePatcher.customSearge` — the user supplies their own
 * searge/mcp mapping chain; disables the auto `forgeBuiltinMCP` hop
 * (pre-1.7 forge bundles searge inside the same src zip).
 */
fun MinecraftEnvironment.customSearge() {
    JarModEnvState.customSearge(environmentId, true)
}

/**
 * Opt-in: resolve `org.ow2.asm:asm-all:5.2` for FML's `asm-all` dyn
 * lib instead of the era's 4.x build — Java 8 classfile support in
 * dev (works wherever the dyn-lib mechanism exists, incl. 1.6.4).
 * The resolved jar is copied to `runDir/lib` under the filename
 * `CoreFMLLibraries` expects.
 */
fun MinecraftEnvironment.useAsm52() {
    JarModEnvState.useAsm52(environmentId, true)
}

/** Register a JarModAgent transformer class (applied at run time and,
 *  when compile-time transforms land, during remap). */
fun MinecraftEnvironment.jarmodTransformer(transform: String) {
    xyz.wagyourtail.unimined.transform.jarmod.internal.JarModEnvState
        .addTransforms(environmentId, listOf(transform))
}

/** Declare a jarModAgent transformer set. */
fun MinecraftEnvironment.jarmodTransforms(transforms: List<String>) {
    xyz.wagyourtail.unimined.transform.jarmod.internal.JarModEnvState
        .addTransforms(environmentId, transforms)
}

/** 1.x `jarModPatcher.deleteMetaInf` — strip the base jar's
 *  META-INF entirely during the merge (signature material is
 *  always dropped either way). */
fun MinecraftEnvironment.deleteMetaInf(disable: Boolean = true) {
    JarModEnvState.deleteMetaInf(environmentId, disable)
}
