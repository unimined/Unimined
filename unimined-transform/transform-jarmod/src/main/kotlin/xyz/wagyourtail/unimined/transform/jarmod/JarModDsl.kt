package xyz.wagyourtail.unimined.transform.jarmod

import xyz.wagyourtail.unimined.minecraft.MinecraftEnvironment

/**
 * Declare a jarmod environment — shorthand for `loader("jarmod", v)`
 * where [version] is the JarModAgent version. Merge inputs come from
 * the env's `jarMod<envId>` configuration (any dependency type).
 */
fun MinecraftEnvironment.jarmod(version: String) {
    loader("jarmod", version)
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
