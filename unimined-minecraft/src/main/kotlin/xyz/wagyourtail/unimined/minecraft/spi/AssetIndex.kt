package xyz.wagyourtail.unimined.minecraft.spi

import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider

/**
 * The resolved Minecraft asset index for an environment.
 */
interface AssetIndex {

    /** Asset index id, e.g. `"17"`, `"legacy"`, `"pre-1.6"`. */
    val id: String

    /** The downloaded asset index file. */
    val file: Provider<RegularFile>

}
