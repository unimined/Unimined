package xyz.wagyourtail.unimined.internal.mods

import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.jetbrains.annotations.ApiStatus
import xyz.wagyourtail.unimined.api.minecraft.MinecraftConfig
import xyz.wagyourtail.unimined.api.mod.ModRemapConfig
import xyz.wagyourtail.unimined.api.mod.ModsConfig
import xyz.wagyourtail.unimined.api.unimined
import xyz.wagyourtail.unimined.mapping.Namespace
import xyz.wagyourtail.unimined.util.FinalizeOnRead
import xyz.wagyourtail.unimined.util.defaultedMapOf
import xyz.wagyourtail.unimined.util.getField
import xyz.wagyourtail.unimined.util.withSourceSet
import java.io.File
import java.nio.file.Path
import kotlin.io.path.createDirectories

class ModsProvider(val project: Project, val minecraft: MinecraftConfig) : ModsConfig() {

    private val remapConfigs = mutableMapOf<Set<Configuration>, ModRemapProvider.() -> Unit>()

    val modImplementation = project.configurations.maybeCreate("modImplementation".withSourceSet(minecraft.sourceSet)).also {
        minecraft.sourceSet.apply {
            compileClasspath += it
            runtimeClasspath += it
        }
        remap(it)
    }

    private var default by FinalizeOnRead<ModRemapProvider.() -> Unit> {}

    val remapConfigsResolved = mutableMapOf<Configuration, ModRemapProvider>()

    fun modTransformFolder(): Path {
        return project.unimined.getLocalCache().resolve("modTransform").createDirectories()
    }

    override fun remap(config: List<Configuration>, action: ModRemapConfig.() -> Unit) {
        val intersect = remapConfigs.keys.firstOrNull { config.intersect(it).isNotEmpty() }?.intersect(config.toSet())
        if (intersect != null) {
            throw IllegalArgumentException("cannot have configuration(s) in multiple remaps $intersect")
        }
        val configSet = config.toSet()
        val old = remapConfigs[configSet]
        remapConfigs[configSet] = {
            if (old != null) {
                old()
            } else {
                default()
            }
            action()
        }
    }

    @ApiStatus.Internal
    fun default(action: ModRemapConfig.() -> Unit) {
        val prev: FinalizeOnRead<ModRemapProvider.() -> Unit> = ModsProvider::class.getField("default")!!.getDelegate(this) as FinalizeOnRead<ModRemapProvider.() -> Unit>
        val old: ModRemapProvider.() -> Unit = prev.value as ModRemapProvider.() -> Unit
        default = {
            old(this)
            action()
        }
    }

    override fun modImplementation(action: ModRemapConfig.() -> Unit) {
        val old = remapConfigs[setOf(modImplementation)]
        remapConfigs[setOf(modImplementation)] = {
            if (old != null) {
                old()
            } else {
                default()
            }
            action()
        }
    }

    fun afterEvaluate() {
        for ((config, action) in remapConfigs) {
            val remapSettings = ModRemapProvider(config, project, minecraft)
            for (c in config) {
                remapConfigsResolved[c] = remapSettings
            }
            remapSettings.action()
            if (minecraft.obfuscated) remapSettings.doRemap()
        }
    }

    override fun getClasspath(): Set<File> {
        return remapConfigsResolved.keys.flatMap { it.resolve() }.toSet()
    }

    override fun getClasspathAs(namespace: Namespace, classpath: Set<File>): Set<File> {
        val remapCp = classpath.associateWith { file ->
            remapConfigsResolved.values.firstNotNullOfOrNull { conf -> conf.getConfigForFile(file)?.let { conf to it } }
        }
        val nonRemap = remapCp.mapNotNull { if (it.value == null) it.key else null }
        project.logger.info("[Unimined/ModRemapper] getting classpath as $namespace")
        val remap = remapCp.values.filterNotNull()
        val map = defaultedMapOf<ModRemapProvider, MutableSet<Configuration>> { mutableSetOf() }
        for ((m, c) in remap) {
            map[m].add(c)
        }
        val remapOutputs = mutableSetOf<Configuration>()
        for (m in map.keys) {
            val def = defaultedMapOf<Configuration, Configuration> { project.configurations.detachedConfiguration() }
            m.doRemap(namespace, def)
            remapOutputs.addAll(def.values)
        }
        val files = remapOutputs.flatMap { it.resolve() }
        for (file in nonRemap) {
            project.logger.info("[Unimined/ModRemapper]    unremapped: $file")
        }
        for (file in files) {
            project.logger.info("[Unimined/ModRemapper]    remapped: $file")
        }
        return nonRemap.toSet() + files
    }

}
