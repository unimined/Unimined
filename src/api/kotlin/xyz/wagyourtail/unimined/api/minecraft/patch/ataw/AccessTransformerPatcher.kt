package xyz.wagyourtail.unimined.api.minecraft.patch.ataw

import org.gradle.api.artifacts.Dependency
import org.jetbrains.annotations.ApiStatus
import xyz.wagyourtail.unimined.api.minecraft.patch.MinecraftPatcher
import java.io.File
import java.nio.file.Path

/**
 * these were split into separate interface in 1.2.0 from forge patcher.
 * @revised 1.2.0
 */
interface AccessTransformerPatcher : MinecraftPatcher, AccessConvert {

    /**
     * location of access transformer file to apply to the minecraft jar.
     */
    var accessTransformer: File?

    /**
     * locations of access transformers that are shaded within the minecraft jar.
     * @since 1.2.0
     */
    @set:ApiStatus.Experimental
    var accessTransformerPaths: List<String>

    /**
     * dependency for access transformer processor.
     */
    @set:ApiStatus.Experimental
    var atDependency: Dependency

    /**
     * whether to use the legacy access transformer format.
     * @since 1.4.0
     */
    var legacyATFormat: Boolean

    /**
     * set the access transformer file to apply to the minecraft jar.
     */
    @Deprecated(message = "", replaceWith = ReplaceWith("accessTransformer(file)"))
    fun setAccessTransformer(file: String) {
        accessTransformer = File(file)
    }

    /**
     * set the access transformer file to apply to the minecraft jar.
     * @since 1.0.0
     */
    fun accessTransformer(file: String) {
        accessTransformer = File(file)
    }

    /**
     * set the access transformer file to apply to the minecraft jar.
     * @since 1.0.0
     */
    fun accessTransformer(file: Path) {
        accessTransformer = file.toFile()
    }

    /**
     * set the access transformer file to apply to the minecraft jar.
     * @since 1.0.0
     */
    fun accessTransformer(file: File) {
        accessTransformer = file
    }

    var atMainClass: String
}