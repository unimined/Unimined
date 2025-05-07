package xyz.wagyourtail.unimined.internal.minecraft.patch.jarmod

import org.gradle.api.Project
import xyz.wagyourtail.unimined.api.minecraft.patch.jarmod.JarModPatcher
import xyz.wagyourtail.unimined.api.unimined
import xyz.wagyourtail.unimined.internal.minecraft.MinecraftProvider
import xyz.wagyourtail.unimined.internal.minecraft.patch.AbstractMinecraftTransformer
import xyz.wagyourtail.unimined.api.minecraft.MinecraftJar
import xyz.wagyourtail.unimined.internal.minecraft.transform.fixes.ModLoaderPatches
import xyz.wagyourtail.unimined.util.*
import java.nio.file.FileSystem
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.zip.ZipInputStream
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists

open class JarModMinecraftTransformer(
    project: Project,
    provider: MinecraftProvider,
    jarModProvider: String = "jarMod",
    providerName: String = "JarMod"
): AbstractMinecraftTransformer(
    project, provider, providerName
), JarModPatcher {

    override var deleteMetaInf: Boolean = false

    val jarModConfiguration = project.configurations.maybeCreate(jarModProvider.withSourceSet(provider.sourceSet)).apply {
        if (isTransitive) {
            isTransitive = false
        }
    }

    override val transform = (listOf<(FileSystem) -> Unit>(
        ModLoaderPatches::fixURIisNotHierarchicalException,
        ModLoaderPatches::fixLoadingModFromOtherPackages
    ) + super.transform).toMutableList()

    fun addTransform(pathFilter: (FileSystem) -> Unit) {
        transform.add(pathFilter)
    }

    private val combinedNames by lazy {
        val jarMod = jarModConfiguration.dependencies.sortedBy { "${it.name}-${it.version}" }
        jarMod.joinToString("+") { it.name + "-" + it.version }
    }

    override fun transform(minecraft: MinecraftJar): MinecraftJar {
        if (combinedNames.isEmpty()) {
            return super.transform(minecraft)
        }
        return minecraft.let(consumerApply {
            val target = MinecraftJar(
                minecraft,
                patches = minecraft.patches + providerName + combinedNames
            )
            if (target.path.exists() && !project.unimined.forceReload) {
                return@consumerApply super.transform(target)
            }

            val jarmod = jarModConfiguration.resolve().toMutableSet()

            try {
                Files.copy(path, target.path, StandardCopyOption.REPLACE_EXISTING)
                target.path.openZipFileSystem(mapOf("mutable" to true)).use { out ->
                    if (out.getPath("META-INF").exists() && deleteMetaInf) {
                        out.getPath("META-INF").deleteRecursively()
                    }
                    for (file in jarmod) {
                        file.toPath().forEachInZip { name, stream ->
                            name.substringBeforeLast('/', "").let { path ->
                                if (path.isNotEmpty()) {
                                    Files.createDirectories(out.getPath(path))
                                }
                            }
                            Files.copy(
                                stream,
                                out.getPath(name),
                                StandardCopyOption.REPLACE_EXISTING
                            )
                        }
                    }
                }
            } catch (e: Throwable) {
                target.path.deleteIfExists()
                throw e
            }
            super.transform(target)
        })
    }
}