package xyz.wagyourtail.unimined.api.minecraft.patch.bukkit

import org.jetbrains.annotations.ApiStatus
import java.io.File

/**
 * @since 1.4.0
 */
interface PaperPatcher : CraftbukkitPatcher {

    var build: Int

    fun loader(build: Int) {
        this.build = build
    }

	/**
	 * [version] build number (must be an integer)
	 */
    override fun loader(version: String) {
        this.build = version.toInt()
    }

    @get:ApiStatus.Internal
    @set:ApiStatus.Experimental
    var paper: File

    @set:ApiStatus.Experimental
    var patchName: Regex

}