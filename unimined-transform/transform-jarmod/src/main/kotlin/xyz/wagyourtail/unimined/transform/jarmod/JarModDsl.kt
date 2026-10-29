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
