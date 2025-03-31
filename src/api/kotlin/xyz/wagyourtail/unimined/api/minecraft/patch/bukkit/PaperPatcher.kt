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

    override fun loader(build: String) {
        this.build = build.toInt()
    }

    @get:ApiStatus.Internal
    @set:ApiStatus.Experimental
    var paper: File

    @set:ApiStatus.Experimental
    var patchName: Regex

}