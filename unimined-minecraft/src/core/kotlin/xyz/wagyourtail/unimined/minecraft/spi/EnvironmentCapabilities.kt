package xyz.wagyourtail.unimined.minecraft.spi

import java.io.Serializable

/**
 * Environment feature data a provider reports alongside its artifacts.
 * Runs/IDE/transform hosts read this instead of re-deriving facts like
 * the required Java version or loader family.
 */
data class EnvironmentCapabilities(

    /** Effective Minecraft version id. */
    val minecraftVersion: String,

    /** Loader version when the environment carries a loader, else `null`. */
    val loaderVersion: String? = null,

    /** Loader/platform family — `vanilla`, `fabric`, `forge`, `neoForge`, ... */
    val platform: String = "vanilla",

    /** Java major version the environment requires (toolchain + runs). */
    val javaVersion: Int = 8,

    /** Whether the runtime needs the pre-1.13-style classpath layout. */
    val legacyClasspath: Boolean = false,

    /** Whether client/server data are split into distinct artifacts. */
    val splitData: Boolean = false,

    /** Whether the environment exposes a test-fixtures variant. */
    val testFixtures: Boolean = false,

    /** Whether the pipeline yields binary-only output (no decompile step). */
    val binaryOnly: Boolean = true,

    /** Whether the output is a decompile/recompile style artifact. */
    val decompileRecompile: Boolean = false,

    /** Loader-specific version capabilities, keyed by capability name. */
    val loaderCapabilities: Map<String, String> = emptyMap()

) : Serializable {

    private companion object {
        private const val serialVersionUID: Long = 1L
    }

}
