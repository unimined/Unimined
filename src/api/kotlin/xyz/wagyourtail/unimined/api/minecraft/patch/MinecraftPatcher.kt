package xyz.wagyourtail.unimined.api.minecraft.patch

import groovy.lang.Closure
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.gradle.api.file.FileCollection
import org.jetbrains.annotations.ApiStatus
import org.objectweb.asm.tree.ClassNode
import xyz.wagyourtail.unimined.api.minecraft.task.AbstractRemapJarTask
import xyz.wagyourtail.unimined.api.minecraft.task.RemapJarTask
import xyz.wagyourtail.unimined.mapping.EnvType
import xyz.wagyourtail.unimined.mapping.Namespace
import java.nio.file.FileSystem
import java.nio.file.Path

/**
 * The class responsible for patching minecraft.
 * @see [FabricLikePatcher], [JarModPatcher], [ForgeLikePatcher]
 * @since 0.2.3
 */
interface MinecraftPatcher {

    val supportedEnvs: Set<EnvType>

    fun name(): String {
        return this::class.simpleName ?: error("MinecraftPatcher class simpleName is null, override name() instead")
    }

    @get:ApiStatus.Internal
    val addVanillaLibraries: Boolean

    /**
     * the namespace to use for the production jar.
     */
    @get:ApiStatus.Internal
    @set:ApiStatus.Internal
    var prodNamespace: Namespace

    /**
     * @since 1.4.0
     */
    fun prodNamespace(namespace: String)

    /**
     * @since 0.4.2
     */
    @set:ApiStatus.Experimental
    var onMergeFail: (clientNode: ClassNode, serverNode: ClassNode, fs: ZipArchiveOutputStream, exception: Exception) -> Unit

    /**
     * @since 0.4.2
     */
    @ApiStatus.Experimental
    fun setOnMergeFail(closure: Closure<*>) {
        onMergeFail = { clientNode, serverNode, fs, exception ->
            closure.call(clientNode, serverNode, fs, exception)
        }
    }

    @ApiStatus.Internal
    fun beforeRemapJarTask(remapJarTask: AbstractRemapJarTask, input: Path): Path

    @ApiStatus.Internal
    fun afterRemapJarTask(remapJarTask: AbstractRemapJarTask, output: Path)

    @get:ApiStatus.Internal
    @set:ApiStatus.Experimental
    var unprotectRuntime: Boolean

    @ApiStatus.Internal
    fun configureRemapJar(task: AbstractRemapJarTask)

    @ApiStatus.Internal
    fun createSourcesJar(classpath: FileCollection, patchedJar: Path, outputPath: Path, linemappedPath: Path?, side: EnvType)
}