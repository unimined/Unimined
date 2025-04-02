package xyz.wagyourtail.unimined.api.minecraft.patch.ataw

import xyz.wagyourtail.unimined.api.minecraft.patch.MinecraftPatcher
import java.io.File
import java.nio.file.Path

/**
 * these were split into separate interface in 1.2.0 from fabric patcher.
 * @since 1.2.0
 */
interface AccessWidenerPatcher : MinecraftPatcher, AccessConvert {

    /**
     * location of access widener file to apply to the minecraft jar.
     */
    var accessWidener: File?

    /**
     * set the access widener file to apply to the minecraft jar.
     */
    @Deprecated(message = "", replaceWith = ReplaceWith("accessWidener(file)"))
    fun setAccessWidener(file: String) {
        accessWidener = File(file)
    }

    fun accessWidener(file: String) {
        accessWidener = File(file)
    }

    fun accessWidener(file: Path) {
        accessWidener = file.toFile()
    }

    fun accessWidener(file: File) {
        accessWidener = file
    }

}
