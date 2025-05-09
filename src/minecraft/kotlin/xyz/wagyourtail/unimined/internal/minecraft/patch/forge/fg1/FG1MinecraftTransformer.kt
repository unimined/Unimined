package xyz.wagyourtail.unimined.internal.minecraft.patch.forge.fg1

import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.Dependency
import org.objectweb.asm.ClassReader
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.*
import xyz.wagyourtail.unimined.api.runs.RunConfig
import xyz.wagyourtail.unimined.api.unimined
import xyz.wagyourtail.unimined.internal.mapping.at.AccessTransformerApplier
import xyz.wagyourtail.unimined.api.minecraft.MinecraftJar
import xyz.wagyourtail.unimined.internal.minecraft.patch.forge.ForgeLikeMinecraftTransformer
import xyz.wagyourtail.unimined.internal.minecraft.patch.jarmod.JarModAgentMinecraftTransformer
import xyz.wagyourtail.unimined.internal.minecraft.resolver.Library
import xyz.wagyourtail.unimined.internal.minecraft.transform.merge.ClassMerger
import xyz.wagyourtail.unimined.mapping.Namespace
import xyz.wagyourtail.unimined.util.*
import xyz.wagyourtail.unimined.util.deleteRecursively
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import kotlin.io.path.*

open class FG1MinecraftTransformer(project: Project, val parent: ForgeLikeMinecraftTransformer): JarModAgentMinecraftTransformer(
    project,
    parent.provider,
    providerName = "${parent.providerName}-FG1"
) {
    init {
        project.logger.lifecycle("[Unimined/Forge] Using FG1 transformer")
        parent.accessTransformerPaths = listOf("forge_at.cfg", "fml_at.cfg")
    }

    var resolvedForgeDeps = false

    override val merger: ClassMerger
        get() = parent.merger

    override fun beforeMappingsResolve() {
        val mcVer = if (provider.version == "1.4") "1.4.0" else provider.version
        val forge = parent.forge.dependencies.first()
        provider.mappings.apply {
            if (!parent.customSearge && canCombine)
                provider.mappings {
                    forgeBuiltinMCP(forge.version!!.substringAfter("${mcVer}-"))
                }
        }
    }

    val dynLibs: Set<String>? by lazy {
        val forge = parent.forge.resolve().first().toPath()

        // resolve dyn libs
        forge.readZipInputStreamFor("cpw/mods/fml/relauncher/CoreFMLLibraries.class", false) {
            getDynLibs(it)
        }
    }

    override fun apply() {
        // get and add forge-src to mappings
        parent.forge.dependencies.forEach(jarModConfiguration.dependencies::add)

        if (dynLibs != null) {
            resolveDynLibs(provider.localCache.resolve("fmllibs").toFile(), dynLibs!!)
        }

        super.apply()
    }

    private val forgeDeps: Configuration = project.configurations.maybeCreate("forgeDeps".withSourceSet(provider.sourceSet)).also {
        provider.minecraftLibraries.extendsFrom(it)
    }

    override fun libraryFilter(library: Library): Library? {
        if (library.name.startsWith("org.ow2.asm") && dynLibs?.contains("asm-all-4.0.jar") == true) {
            return null
        }
        return super.libraryFilter(library)
    }

    fun resolveDynLibs(workingDirectory: File, wanted: Set<String>) {
        val path = workingDirectory.toPath().createDirectories()

        val deps = listOf(
            Pair(
                Pair("guava-12.0.1.jar", "guava-12.0.1.jar"), project.dependencies.create(
                    "com.google.guava:guava:12.0.1"
                )
            ),
            Pair(
                Pair("guava-14.0-rc3.jar", "guava-14.0-rc3.jar"), project.dependencies.create(
                    "com.google.guava:guava:14.0-rc3"
                )
            ),
            Pair(
                Pair("asm-all-4.1.jar", "asm-all-4.1.jar"), project.dependencies.create(
                    "org.ow2.asm:asm-all:4.1"
                )
            ),
            Pair(
                Pair("bcprov-jdk15on-1.48.jar", "bcprov-jdk15on-148.jar"), project.dependencies.create(
                    "org.bouncycastle:bcprov-jdk15on:1.48"
                )
            ),
            Pair(
                Pair("bcprov-jdk15on-1.47.jar", "bcprov-jdk15on-147.jar"), project.dependencies.create(
                    "org.bouncycastle:bcprov-jdk15on:1.47"
                )
            ),
        )

        deps.forEach { dep ->
            if (wanted.contains(dep.first.second)) {
                forgeDeps.dependencies.add(dep.second)
            }
        }

        if (wanted.contains("asm-all-4.0.jar")) {
            FG1MinecraftTransformer::class.java.getResourceAsStream("/fmllibs/asm-all-4.0.jar").use { it1 ->
                val bytes = it1!!.readBytes()
                path.resolve("asm-all-4.0.jar")
                    .writeBytes(bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
            }

            forgeDeps.dependencies.add(
                project.dependencies.create(
                    project.files(path.resolve("asm-all-4.0.jar").toString())
                )
            )
        }

        if (wanted.contains("scala-library.jar")) {
            FG1MinecraftTransformer::class.java.getResourceAsStream("/fmllibs/scala-library.jar").use { it1 ->
                val bytes = it1!!.readBytes()
                path.resolve("scala-library.jar")
                    .writeBytes(bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
            }

            forgeDeps.dependencies.add(
                project.dependencies.create(
                    project.files(path.resolve("scala-library.jar").toString())
                )
            )
        }

        if (wanted.contains("argo-small-3.2.jar")) {
            FG1MinecraftTransformer::class.java.getResourceAsStream("/fmllibs/argo-small-3.2.jar").use { it1 ->
                val bytes = it1!!.readBytes()
                path.resolve("argo-small-3.2.jar")
                    .writeBytes(bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
            }

            forgeDeps.dependencies.add(
                project.dependencies.create(
                    project.files(path.resolve("argo-small-3.2.jar").toString())
                )
            )
        }

        if (wanted.contains("argo-2.25.jar")) {
            FG1MinecraftTransformer::class.java.getResourceAsStream("/fmllibs/argo-2.25.jar").use { it1 ->
                val bytes = it1!!.readBytes()
                path.resolve("argo-2.25.jar")
                    .writeBytes(bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
            }

            forgeDeps.dependencies.add(
                project.dependencies.create(
                    project.files(path.resolve("argo-2.25.jar").toString())
                )
            )
        }

        if (wanted.contains("deobfuscation_data_1.5.2.zip")) {
            FG1MinecraftTransformer::class.java.getResourceAsStream("/fmllibs/deobfuscation_data_1.5.2.zip")
                .use { it1 ->
                    val bytes = it1!!.readBytes()
                    path.resolve("deobfuscation_data_1.5.2.zip")
                        .writeBytes(bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
                }

            forgeDeps.dependencies.add(
                project.dependencies.create(
                    project.files(path.resolve("deobfuscation_data_1.5.2.zip").toString())
                )
            )
        }
    }

    override fun applyClientRunTransform(config: RunConfig) {
        super.applyClientRunTransform(config)

        // resolve dyn libs
        val dynLibFolder = config.workingDir.resolve("lib")
        dynLibFolder.mkdirs()
        for (file in forgeDeps.resolve()) {
            if (file.exists() && file.extension != "pom") {
                file.copyTo(dynLibFolder.resolve(file.name), overwrite = true)
            }
        }

        config.jvmArgs("-Dminecraft.applet.TargetDirectory=${config.workingDir.absolutePath}")
        if (parent.mainClass != null) config.mainClass.set(parent.mainClass!!)
    }

    override fun afterRemap(baseMinecraft: MinecraftJar): MinecraftJar {
        return fixForge(baseMinecraft)
    }

    private fun fixForge(baseMinecraft: MinecraftJar): MinecraftJar {
        if (!baseMinecraft.patches.contains("fixForge")) {
            val target = MinecraftJar(
                baseMinecraft,
                patches = baseMinecraft.patches + "fixForge",
            )

            if (target.path.exists() && !project.unimined.forceReload) {
                return target
            }

            Files.copy(baseMinecraft.path, target.path, StandardCopyOption.REPLACE_EXISTING)
            try {
                target.path.openZipFileSystem(mapOf("mutable" to true)).use { out ->
                    val dynPath = out.getPath("cpw/mods/fml/relauncher/CoreFMLLibraries.class")

                    val dyn = if (dynPath.exists()) dynPath.inputStream().use { it ->
                        getDynLibs(it)
                    } else setOf()

                    if (dyn.any { it.contains("argo") }) {
                        out.getPath("argo").apply {
                            if (exists()) {
                                deleteRecursively()
                            }
                        }
                    }
                    if (dyn.any { it.contains("bcprov") }) {
                        out.getPath("org/bouncycastle").apply {
                            if (exists()) {
                                deleteRecursively()
                            }
                        }
                    }
                }
            } catch (e: Throwable) {
                target.path.deleteIfExists()
                throw e
            }
            return target
        }
        return baseMinecraft
    }

    private fun getDynLibs(stream: InputStream): Set<String> {
        project.logger.lifecycle("[Unimined/ForgeTransformer] Getting dynamic libraries from FML")
        val classReader = ClassReader(stream)

        val classNode = ClassNode()
        classReader.accept(classNode, 0)

        // get string array stored to libraries from clinit
        val clInit = classNode.methods.first { it.name == "<clinit>" }
        val iterator = clInit.instructions.iterator()
        val outSet = mutableSetOf<String>()
        var arrayFlag = false
        while (iterator.hasNext()) {
            val insn = iterator.next()
            if (insn is TypeInsnNode && insn.opcode == Opcodes.ANEWARRAY && insn.desc == "java/lang/String") {
                arrayFlag = true
            }
            if (arrayFlag) {
                if (insn is LdcInsnNode) {
                    outSet.add(insn.cst as String)
                }
                if (insn is MethodInsnNode && insn.opcode == Opcodes.INVOKESTATIC && insn.name == "debfuscationDataName") {
                    outSet.add("deobfuscation_data_${provider.version}.zip")
                }
            }
            if (insn is FieldInsnNode && insn.opcode == Opcodes.PUTSTATIC) {
                if (insn.name == "libraries") {
                    break
                } else {
                    arrayFlag = false
                    outSet.clear()
                }
            }
        }
        return outSet
    }
}