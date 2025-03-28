package xyz.wagyourtail.unimined.internal.minecraft.patch.bukkit

import org.gradle.api.Project
import org.gradle.api.artifacts.ExternalDependency
import org.w3c.dom.Element
import xyz.wagyourtail.unimined.api.mapping.MappingsConfig
import xyz.wagyourtail.unimined.api.minecraft.patch.bukkit.CraftbukkitPatcher
import xyz.wagyourtail.unimined.api.runs.RunConfig
import xyz.wagyourtail.unimined.api.unimined
import xyz.wagyourtail.unimined.api.uniminedMaybe
import xyz.wagyourtail.unimined.internal.minecraft.MinecraftProvider
import xyz.wagyourtail.unimined.internal.minecraft.patch.AbstractMinecraftTransformer
import xyz.wagyourtail.unimined.api.minecraft.MinecraftJar
import xyz.wagyourtail.unimined.internal.minecraft.patch.bukkit.buildtools.BuildToolsExecutor
import xyz.wagyourtail.unimined.mapping.EnvType
import xyz.wagyourtail.unimined.mapping.Namespace
import xyz.wagyourtail.unimined.mapping.jvms.four.three.three.MethodDescriptor
import xyz.wagyourtail.unimined.mapping.jvms.four.three.two.FieldDescriptor
import xyz.wagyourtail.unimined.mapping.jvms.four.two.one.InternalName
import xyz.wagyourtail.unimined.mapping.resolver.MappingResolver
import xyz.wagyourtail.unimined.mapping.visitor.ClassVisitor
import xyz.wagyourtail.unimined.mapping.visitor.FieldVisitor
import xyz.wagyourtail.unimined.mapping.visitor.MappingVisitor
import xyz.wagyourtail.unimined.mapping.visitor.MethodVisitor
import xyz.wagyourtail.unimined.mapping.visitor.delegate.Delegator
import xyz.wagyourtail.unimined.mapping.visitor.delegate.delegator
import xyz.wagyourtail.unimined.util.*
import java.io.File
import kotlin.io.path.copyTo

open class CraftbukkitMinecraftTransformer(
    project: Project,
    provider: MinecraftProvider,
    providerName: String = "craftbukkit"
) : AbstractMinecraftTransformer(project, provider, providerName), CraftbukkitPatcher {

    val cache by lazy {
        project.unimined.getLocalCache(provider.sourceSet).resolve("spigot")
    }

    override var loader: String by MustSet()

    val spigotLibraries = project.configurations.maybeCreate("spigotLibraries".withSourceSet(provider.sourceSet)).apply {
        provider.sourceSet.compileClasspath += this
        provider.sourceSet.runtimeClasspath += this
    }

    override var classPathPluginLoader = project.configurations.maybeCreate("classpathPluginLoader".withSourceSet(provider.sourceSet)).apply {
        provider.minecraftLibraries.extendsFrom(this)
    }

    val cplFile by lazy {
        classPathPluginLoader.resolve().first { it.extension == "jar" }.toPath()
    }

    val executor by LazyMutable {
        BuildToolsExecutor(
            project,
            provider,
            loader,
            cache,
            target
        )
    }

    override fun defaultProdNamespace() = provider.mappings.checkedNs("spigotProd")

    val CPL_GROUPS = "cpl.pluginGroups"

    override fun agentVersion(vers: String) {
        project.unimined.wagYourMaven("releases")
        project.unimined.wagYourMaven("snapshots")
        classPathPluginLoader.dependencies.add(
            project.dependencies.create("xyz.wagyourtail.unimined:classpath-plugin-loader:$vers:all").also {
                (it as ExternalDependency).isTransitive = false
            }
        )
    }

    init {
        unprotectRuntime = true
    }

    override fun beforeMappingsResolve() {
        super.beforeMappingsResolve()
        provider.mappings {
            spigotProd()
        }
    }

    open val exclude = setOf(
        "minecraft-server",
        "netty-transport-native-epoll"
    )

    override fun apply() {
        project.configurations.getByName("runtimeOnly".withSourceSet(provider.sourceSet)).dependencies.addAll(
            listOf(
                project.dependencies.create("org.ow2.asm:asm:9.5"),
                project.dependencies.create("org.ow2.asm:asm-commons:9.5"),
                project.dependencies.create("org.ow2.asm:asm-tree:9.5")
            )
        )
        if (classPathPluginLoader.dependencies.isEmpty()) {
            agentVersion("0.1.0-SNAPSHOT")
        }
        executor.cloneRepos()
        project.unimined.spigot()

        val deps = executor.targetPom.getElementsByTagName("dependencies").item(0).childNodes

        (0 until deps.length).forEach { i ->
            val it = deps.item(i)
            if (it.nodeName == "dependency") {
                it as Element
                val groupId = it.getElementsByTagName("groupId").item(0).textContent
                val artifactId = it.getElementsByTagName("artifactId").item(0).textContent
                var version = it.getElementsByTagName("version").item(0).textContent
                if (version == "\${project.version}") {
                    version = executor.version
                }
                // skip bukkit/spigot
                if (exclude.contains(artifactId)) return@forEach
                val dep = project.dependencies.create("$groupId:$artifactId:$version")
                spigotLibraries.dependencies.add(dep)
            }
        }
    }

    var target: BuildToolsExecutor.BuildTarget by FinalizeOnRead(BuildToolsExecutor.BuildTarget.CRAFTBUKKIT)

    override fun transform(minecraft: MinecraftJar): MinecraftJar {
        if (minecraft.envType != EnvType.SERVER) throw IllegalArgumentException("Craftbukkit can only be applied to server jars")
        val outputFile = executor.runBuildTools()

        // copy output file to
        val patchedJar = MinecraftJar(
            minecraft,
            name = providerName,
            patches = minecraft.patches + listOf(executor.version + "-${executor.buildInfo.name}"),
            mappingNamespace = provider.mappings.checkedNs("spigotProd"),
        )

        outputFile.copyTo(patchedJar.path, overwrite = true)

        return super.transform(patchedJar)
    }

    private fun MappingResolver<out MappingsConfig<*>>.spigotProd() {
        postProcessDependency("spigotProd", {
            spigotDev()
        }) {
            mapNamespace("spigotDev", "spigotProd")
            provides("spigotProd" to false)

            insertInto.add {
                it.delegator(object : Delegator() {
                    val official = Namespace("official")
                    val spigotProd = Namespace("spigotProd")

                    override fun visitClass(
                        delegate: MappingVisitor,
                        names: Map<Namespace, InternalName>
                    ): ClassVisitor? {
                        var spigotProdName = names[spigotProd]
                        if (executor.versionInfo.mappingsUrl == null) {
                            if (spigotProdName != null) {
                                val newName = "net/minecraft/server/v${executor.minecraftVersion}/" + spigotProdName.value.substringAfterLast("/")
                                val names = names.toMutableMap()
                                names[spigotProd] = InternalName.unchecked(newName)
                                return super.visitClass(delegate, names)
                            }
                        }
                        return super.visitClass(delegate, names)
                    }

                    override fun visitMethod(
                        delegate: ClassVisitor,
                        names: Map<Namespace, Pair<String, MethodDescriptor?>>
                    ): MethodVisitor? {
                        return null
                    }

                    override fun visitField(
                        delegate: ClassVisitor,
                        names: Map<Namespace, Pair<String, FieldDescriptor?>>
                    ): FieldVisitor? {
                        return null
                    }

                })
            }
        }
    }


    val groups: String by lazy {
        val groups = sortProjectSourceSets().mapValues { it.value.toMutableSet() }.toMutableMap()
        // detect non-fabric groups
        for ((proj, sourceSet) in groups.keys.toSet()) {
            if (proj.uniminedMaybe?.minecrafts?.get(sourceSet)?.mcPatcher !is CraftbukkitPatcher) {
                // merge with current
                proj.logger.warn("[Unimined/FabricLike] Non-fabric ${(proj to sourceSet).toPath()} found in fabric classpath groups, merging with current (${(project to provider.sourceSet).toPath()}), this should've been manually specified with `combineWith`")
                groups[this.project to this.provider.sourceSet]!! += groups[proj to sourceSet]!!
                groups.remove(proj to sourceSet)
            }
        }
        project.logger.info("[Unimined/FabricLike] Classpath groups: ${groups.map { it.key.toPath() + " -> " + it.value.joinToString(", ") { it.toPath() } }.joinToString("\n    ")}")
        groups.map { entry -> entry.value.flatMap { it.second.output }.joinToString(File.pathSeparator) { it.absolutePath } }.joinToString(
            File.pathSeparator.repeat(2))
    }


    override fun applyServerRunTransform(config: RunConfig) {
        super.applyServerRunTransform(config)
        config.mainClass.set("org.bukkit.craftbukkit.Main")
        config.jvmArgs("-javaagent:${cplFile.toAbsolutePath()}")
        config.jvmArgs("-D${CPL_GROUPS}=$groups")
    }

}