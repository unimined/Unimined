package xyz.wagyourtail.unimined.internal.minecraft.patch.rift

import net.neoforged.accesstransformer.AccessTransformer
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.ModuleDependency
import xyz.wagyourtail.unimined.api.minecraft.MinecraftJar
import xyz.wagyourtail.unimined.api.minecraft.patch.ataw.AccessConvert
import xyz.wagyourtail.unimined.api.minecraft.patch.ataw.AccessTransformerPatcher
import xyz.wagyourtail.unimined.api.minecraft.patch.rift.RiftPatcher
import xyz.wagyourtail.unimined.api.runs.RunConfig
import xyz.wagyourtail.unimined.api.unimined
import xyz.wagyourtail.unimined.internal.minecraft.MinecraftProvider
import xyz.wagyourtail.unimined.internal.minecraft.patch.AbstractMinecraftTransformer
import xyz.wagyourtail.unimined.internal.minecraft.patch.access.AccessConvertImpl
import xyz.wagyourtail.unimined.internal.minecraft.patch.access.transformer.AccessTransformerMinecraftTransformer
import xyz.wagyourtail.unimined.util.FinalizeOnRead
import xyz.wagyourtail.unimined.util.withSourceSet
import java.io.File

open class RiftMinecraftTransformer(
    project: Project,
    provider: MinecraftProvider
) : AbstractMinecraftTransformer(project, provider, "Rift"), RiftPatcher, AccessTransformerMinecraftTransformer, AccessConvert by AccessConvertImpl(project, provider) {

    private var rift: Dependency? = null

    override var accessTransformer: File? by FinalizeOnRead(null)
    override var accessTransformerPaths: List<String> by FinalizeOnRead(emptyList())
    override var atDependency: Dependency by FinalizeOnRead(AccessTransformerMinecraftTransformer.getDefaultDependency(project, provider))
    override var atMainClass: String by FinalizeOnRead(AccessTransformerMinecraftTransformer.getDependencyMainClass(project, provider))

    override fun loader(dep: Any, action: Dependency.() -> Unit) {
        rift = (if (dep is String && !dep.contains(":")) {
                project.dependencies.create("org.dimdev:rift:$dep")
            } else project.dependencies.create(dep)).apply(action)
    }

    init {
        project.unimined.wagYourMaven("releases")
        project.unimined.minecraftForgeMaven()
        project.unimined.spongeMaven()
    }

    override fun apply() {
        createRiftDependency("org.spongepowered:mixin:0.7.11-SNAPSHOT")
        createRiftDependency("net.minecraft:launchwrapper:1.12")
        createRiftDependency("org.ow2.asm:asm:6.2", true)
        createRiftDependency("org.ow2.asm:asm-commons:6.2", true)
        createRiftDependency("org.ow2.asm:asm-tree:6.2", true)

        if (rift == null) rift = project.dependencies.create("org.dimdev:rift:${provider.version}")

        project.configurations.getByName("modImplementation".withSourceSet(provider.sourceSet)).dependencies.add(rift)

        super.apply()
    }

    override fun afterRemap(baseMinecraft: MinecraftJar): MinecraftJar {
        return super<AccessTransformerMinecraftTransformer>.afterRemap(baseMinecraft)
    }

    override fun applyClientRunTransform(config: RunConfig) {
        config.mainClass.set("net.minecraft.launchwrapper.Launch")

        config.args(
            "--tweakClass",
            "org.dimdev.riftloader.launch.RiftLoaderClientTweaker"
        )
    }

    override fun applyServerRunTransform(config: RunConfig) {
        config.mainClass.set("net.minecraft.launchwrapper.Launch")

        config.args(
            "--tweakClass",
            "org.dimdev.riftloader.launch.RiftLoaderServerTweaker"
        )
    }

    private fun createRiftDependency(name: String, transitive: Boolean = false) {
        val dep = project.dependencies.create(name) as ModuleDependency

        dep.setTransitive(transitive)

        provider.minecraftLibraries.dependencies.add(dep)
    }

}