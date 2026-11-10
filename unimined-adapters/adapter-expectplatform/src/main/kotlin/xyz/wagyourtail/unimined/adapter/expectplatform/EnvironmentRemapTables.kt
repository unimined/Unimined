package xyz.wagyourtail.unimined.adapter.expectplatform

/**
 * Built-in `@Environment` annotation remap tables, keyed on the
 * environment's loader — ExpectPlatform's own `@Environment`/`EnvType`
 * descriptors rewritten to the loader's native side annotations, so
 * loaders scanning raw jar bytes (or the agent at dev runtime) see
 * their own annotation types.
 *
 * These are the same tables ExpectPlatform's README documents; the
 * adapter fills them in so `platform("fabric")` needs no hand-written
 * map. An explicit `remap` argument or `remap.set(...)` replaces the
 * table entirely (Gradle convention semantics).
 */
internal object EnvironmentRemapTables {

    private const val ENV = "xyz/wagyourtail/unimined/expect/annotation/Environment"
    private const val ENV_TYPE = "$ENV\$EnvType"

    private val FABRIC = mapOf(
        ENV to "net/fabricmc/api/Environment",
        ENV_TYPE to "net/fabricmc/api/EnvType",
    )

    private val QUILT = mapOf(
        ENV to "org/quiltmc/loader/api/minecraft/MinecraftOnlyIn",
        ENV_TYPE to "org/quiltmc/loader/api/minecraft/EnvType",
    )

    /** Forge ≥1.13 (modern userdev) + LexForge-era. `Dist` names its
     *  server constant `DEDICATED_SERVER`, so the enum constant remaps
     *  alongside the types. */
    private val FORGE_MODERN = mapOf(
        ENV to "net/minecraftforge/api/distmarker/OnlyIn",
        ENV_TYPE to "net/minecraftforge/api/distmarker/Dist",
        "$ENV_TYPE.SERVER" to "DEDICATED_SERVER",
    )

    /** Forge 1.8–1.12.2 (FG2/FG3-era `fml.relauncher`). `Side` already
     *  uses CLIENT/SERVER names. */
    private val FORGE_FML = mapOf(
        ENV to "net/minecraftforge/fml/relauncher/SideOnly",
        ENV_TYPE to "net/minecraftforge/fml/relauncher/Side",
    )

    /** Forge ≤1.7.10 (FG1-era `cpw.mods.fml`). */
    private val FORGE_CPW = mapOf(
        ENV to "cpw/mods/fml/relauncher/SideOnly",
        ENV_TYPE to "cpw/mods/fml/relauncher/Side",
    )

    /** NeoForge — same shape as modern forge, different package. */
    private val NEOFORGE = mapOf(
        ENV to "net/neoforged/api/distmarker/OnlyIn",
        ENV_TYPE to "net/neoforged/api/distmarker/Dist",
        "$ENV_TYPE.SERVER" to "DEDICATED_SERVER",
    )

    /** Loaders that consume fabric-loader's api annotations. */
    private val FABRIC_FAMILY = setOf(
        "fabric", "legacyfabric", "babric", "ornithe"
    )

    /**
     * The remap table for [loaderName] on Minecraft [mcVersion], or
     * `null` when the loader has no side-annotation convention
     * (vanilla, modloader, jarmod, bukkit family, rift, foxloader,
     * liteloader, transform-only loaders).
     */
    fun forLoader(loaderName: String?, mcVersion: String?): Map<String, String>? =
        when (loaderName?.lowercase()) {
            in FABRIC_FAMILY -> FABRIC
            "quilt" -> QUILT
            "neoforge" -> NEOFORGE
            "lexforge" -> FORGE_MODERN
            "forge", "cleanroom" -> forgeFor(mcVersion)
            else -> null
        }

    /**
     * Forge's side annotations moved packages across eras — pick by
     * MC version: ≥1.13 `api.distmarker`, 1.8–1.12 `fml.relauncher`,
     * everything earlier `cpw.mods.fml` (FG1 era; covers c/b/a/rd ids
     * and pre-release lines). Cleanroom pins 1.12 → fml table.
     */
    private fun forgeFor(mcVersion: String?): Map<String, String> {
        val minor = mcVersion?.let {
            Regex("^1\\.(\\d+)").find(it)?.groupValues?.get(1)?.toIntOrNull()
        }
        return when {
            minor == null -> FORGE_CPW
            minor >= 13 -> FORGE_MODERN
            minor >= 8 -> FORGE_FML
            else -> FORGE_CPW
        }
    }

}
