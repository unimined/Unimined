package xyz.wagyourtail.unimined.internal.minecraft.patch.jarmod

import kotlinx.coroutines.runBlocking
import org.gradle.api.Project
import xyz.wagyourtail.unimined.api.minecraft.MinecraftJar
import xyz.wagyourtail.unimined.api.minecraft.patch.jarmod.JarModPatcher
import xyz.wagyourtail.unimined.api.unimined
import xyz.wagyourtail.unimined.internal.minecraft.MinecraftProvider
import xyz.wagyourtail.unimined.internal.minecraft.patch.AbstractMinecraftTransformer
import xyz.wagyourtail.unimined.internal.minecraft.transform.fixes.ModLoaderPatches
import xyz.wagyourtail.unimined.mapping.jvms.four.two.one.InternalName
import xyz.wagyourtail.unimined.util.*
import java.nio.file.FileSystem
import java.nio.file.Files
import java.nio.file.StandardCopyOption
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

    fun addExtraInnerClassMappings(prePatched: MinecraftJar, postPatched: MinecraftJar) {
        val prePatchClasses = prePatched.path.readZipContents().filter { it.endsWith(".class") }.map { it.removeSuffix(".class") }
        val postPatchClasses = postPatched.path.readZipContents().filter { it.endsWith(".class") }.map { it.removeSuffix(".class") }

        val namespace = prePatched.mappingNamespace
        val addedClasses = (postPatchClasses - prePatchClasses).sorted()

        runBlocking {
            val mappings = provider.mappings.resolve()
            for (className in addedClasses) {
                if (!className.contains("$") || mappings.getClass(namespace, InternalName.unchecked(className)) != null) continue
                val outerName = className.substringBeforeLast("$")
                val innerName = className.substringAfterLast("$")
                val outerMapping = mappings.getClass(namespace, InternalName.unchecked(outerName))
                if (outerMapping != null) {
                    val names = outerMapping.names.mapValues { InternalName.unchecked("${it.value}$${innerName}") }.toMutableMap()
                    for ((ns, name) in names.toMap()) {
                        if (mappings.getClass(ns, name) != null) {
                            names.remove(ns)
                        }
                    }
                    if (names.isNotEmpty()) {
                        project.logger.lifecycle("[Unimined/JarMod ${project.path}:${provider.sourceSet}] Adding mappings for added inner class $className, $names")
                        mappings.visitClass(names).visitEnd()
                    }
                }
            }
        }
    }

    override fun transform(minecraft: MinecraftJar): MinecraftJar {
        if (combinedNames.isEmpty()) {
            return minecraft
        }
        return minecraft.let(consumerApply {
            val target = MinecraftJar(
                minecraft,
                patches = minecraft.patches + providerName + combinedNames
            )
            if (target.path.exists() && !project.unimined.forceReload) {
                addExtraInnerClassMappings(minecraft, target)

                return@consumerApply target
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
                    transform.forEach { it(out) }
                }
            } catch (e: Throwable) {
                target.path.deleteIfExists()
                throw e
            }

            addExtraInnerClassMappings(minecraft, target)

            target
        })
    }
}