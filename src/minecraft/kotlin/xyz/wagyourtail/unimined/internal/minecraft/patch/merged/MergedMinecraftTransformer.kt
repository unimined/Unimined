package xyz.wagyourtail.unimined.internal.minecraft.patch.merged

import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.gradle.api.Project
import org.jetbrains.annotations.ApiStatus
import org.objectweb.asm.tree.ClassNode
import xyz.wagyourtail.unimined.api.minecraft.patch.*
import xyz.wagyourtail.unimined.api.minecraft.patch.ataw.AccessTransformerPatcher
import xyz.wagyourtail.unimined.api.minecraft.patch.ataw.AccessWidenerPatcher
import xyz.wagyourtail.unimined.api.minecraft.patch.bukkit.CraftbukkitPatcher
import xyz.wagyourtail.unimined.api.minecraft.patch.bukkit.SpigotPatcher
import xyz.wagyourtail.unimined.api.minecraft.patch.fabric.FabricLikePatcher
import xyz.wagyourtail.unimined.api.minecraft.patch.forge.ForgeLikePatcher
import xyz.wagyourtail.unimined.api.minecraft.patch.forge.MinecraftForgePatcher
import xyz.wagyourtail.unimined.api.minecraft.patch.forge.NeoForgedPatcher
import xyz.wagyourtail.unimined.api.minecraft.patch.jarmod.JarModAgentPatcher
import xyz.wagyourtail.unimined.api.minecraft.patch.rift.RiftPatcher
import xyz.wagyourtail.unimined.api.runs.RunConfig
import xyz.wagyourtail.unimined.api.minecraft.task.AbstractRemapJarTask
import xyz.wagyourtail.unimined.internal.minecraft.MinecraftProvider
import xyz.wagyourtail.unimined.internal.minecraft.patch.AbstractMinecraftTransformer
import xyz.wagyourtail.unimined.api.minecraft.MinecraftJar
import xyz.wagyourtail.unimined.api.minecraft.patch.bukkit.PaperPatcher
import xyz.wagyourtail.unimined.api.minecraft.patch.fabric.LegacyFabricPatcher
import xyz.wagyourtail.unimined.api.minecraft.patch.forge.CleanroomPatcher
import xyz.wagyourtail.unimined.api.minecraft.patch.liteloader.LiteLoaderPatcher
import xyz.wagyourtail.unimined.internal.minecraft.patch.access.transformer.AccessTransformerMinecraftTransformer
import xyz.wagyourtail.unimined.internal.minecraft.patch.access.widener.AccessWidenerMinecraftTransformer
import xyz.wagyourtail.unimined.internal.minecraft.patch.bukkit.CraftbukkitMinecraftTransformer
import xyz.wagyourtail.unimined.internal.minecraft.patch.bukkit.PaperMinecraftTransformer
import xyz.wagyourtail.unimined.internal.minecraft.patch.bukkit.SpigotMinecraftTransformer
import xyz.wagyourtail.unimined.internal.minecraft.patch.fabric.*
import xyz.wagyourtail.unimined.internal.minecraft.patch.forge.CleanroomMinecraftTransformer
import xyz.wagyourtail.unimined.internal.minecraft.patch.forge.MinecraftForgeMinecraftTransformer
import xyz.wagyourtail.unimined.internal.minecraft.patch.forge.NeoForgedMinecraftTransformer
import xyz.wagyourtail.unimined.internal.minecraft.patch.jarmod.JarModAgentMinecraftTransformer
import xyz.wagyourtail.unimined.internal.minecraft.patch.liteloader.LiteLoaderMinecraftTransformer
import xyz.wagyourtail.unimined.internal.minecraft.patch.rift.RiftMinecraftTransformer
import xyz.wagyourtail.unimined.internal.minecraft.resolver.Library
import xyz.wagyourtail.unimined.util.FinalizeOnRead
import java.nio.file.Path

class MergedMinecraftTransformer(project: Project, provider: MinecraftProvider): AbstractMinecraftTransformer(project, provider, "merged"), MergedPatcher {

    val patchers = mutableListOf<AbstractMinecraftTransformer>()

    var customLibraryFilter: ((Library) -> Library?)? by FinalizeOnRead(null)

    override var onMergeFail: (clientNode: ClassNode, serverNode: ClassNode, fs: ZipArchiveOutputStream, exception: Exception) -> Unit
        get() = patchers.first().onMergeFail
        set(value) {
            patchers.first().onMergeFail = value
        }

    override var canCombine: Boolean
        get() = patchers.first().canCombine
        set(value) {
            patchers.forEach { it.canCombine = value }
        }

    override fun merge(clientjar: MinecraftJar, serverjar: MinecraftJar): MinecraftJar {
        val mc = patchers.first().merge(clientjar, serverjar)
        if (mc.mappingNamespace != provider.mappings.checkedNs("official")) {
            // remap back to official
            return provider.minecraftRemapper.provide(mc, provider.mappings.checkedNs("official"))
        }
        return mc
    }

    override fun transform(minecraft: MinecraftJar): MinecraftJar {
        return patchers.fold(minecraft) {
            acc, patcher -> patcher.transform(acc)
        }
    }

    override fun afterRemap(baseMinecraft: MinecraftJar): MinecraftJar {
        return patchers.fold(baseMinecraft) {
            acc, patcher -> patcher.afterRemap(acc)
        }
    }

    override fun beforeRemapJarTask(remapJarTask: AbstractRemapJarTask, input: Path): Path {
        return patchers.fold(input) {
            acc, patcher -> patcher.beforeRemapJarTask(remapJarTask, acc)
        }
    }

    override fun beforeMappingsResolve() {
        patchers.forEach { it.beforeMappingsResolve() }
    }

    override fun afterEvaluate() {
        patchers.forEach { it.afterEvaluate() }
    }

    override fun afterRemapJarTask(remapJarTask: AbstractRemapJarTask, output: Path) {
        patchers.forEach { it.afterRemapJarTask(remapJarTask, output) }
    }

    override fun applyExtraLaunches() {
        patchers.forEach { it.applyExtraLaunches() }
    }

    override fun applyClientRunTransform(config: RunConfig) {
        patchers.forEach { it.applyClientRunTransform(config) }
    }

    override fun applyServerRunTransform(config: RunConfig) {
        patchers.forEach { it.applyServerRunTransform(config) }
    }

    override fun apply() {
        patchers.forEach { it.apply() }
    }

    @Deprecated("use prodNamespace instead", replaceWith = ReplaceWith("prodNamespace"))
    override fun setProdNamespace(namespace: String) {
        prodNamespace(namespace)
    }

    override fun prodNamespace(namespace: String) {
        patchers.forEach { it.prodNamespace(namespace) }
    }

    override fun fabric(action: FabricLikePatcher.() -> Unit) {
        val fabric = OfficialFabricMinecraftTransformer(project, provider)
        fabric.action()
        patchers.add(fabric)
    }

    override fun legacyFabric(action: LegacyFabricPatcher.() -> Unit) {
        val fabric = LegacyFabricMinecraftTransformer(project, provider)
        fabric.action()
        patchers.add(fabric)
    }

    override fun babric(action: FabricLikePatcher.() -> Unit) {
        val fabric = BabricMinecraftTransformer(project, provider)
        fabric.action()
        patchers.add(fabric)
    }

    override fun ornitheFabric(action: LegacyFabricPatcher.() -> Unit) {
        val fabric = OrnitheFabricMinecraftTransformer(project, provider)
        fabric.action()
        patchers.add(fabric)
    }

    override fun quilt(action: FabricLikePatcher.() -> Unit) {
        val fabric = QuiltMinecraftTransformer(project, provider)
        fabric.action()
        patchers.add(fabric)
    }

    override fun flint(action: FabricLikePatcher.() -> Unit) {
        val flint = FlintMinecraftTransformer(project, provider)
        flint.action()
        patchers.add(flint)
    }

    @Deprecated("Please specify which forge.", replaceWith = ReplaceWith("minecraftForge(action)"))
    override fun forge(action: ForgeLikePatcher<*>.() -> Unit) {
        minecraftForge(action)
    }

    override fun minecraftForge(action: MinecraftForgePatcher<*>.() -> Unit) {
        val forge = MinecraftForgeMinecraftTransformer(project, provider)
        forge.action()
        patchers.add(forge)
    }

    override fun neoForge(action: NeoForgedPatcher<*>.() -> Unit) {
        val forge = NeoForgedMinecraftTransformer(project, provider)
        forge.action()
        patchers.add(forge)
    }

    override fun cleanroom(action: CleanroomPatcher<*>.() -> Unit) {
        val cleanroom = CleanroomMinecraftTransformer(project, provider)
        cleanroom.action()
        patchers.add(cleanroom)
    }

    override fun jarMod(action: JarModAgentPatcher.() -> Unit) {
        val jarMod = JarModAgentMinecraftTransformer(project, provider)
        jarMod.action()
        patchers.add(jarMod)
    }

    override fun accessWidener(action: AccessWidenerPatcher.() -> Unit) {
        val aw = AccessWidenerMinecraftTransformer.DefaultTransformer(project, provider)
        aw.action()
        patchers.add(aw)
    }

    override fun accessTransformer(action: AccessTransformerPatcher.() -> Unit) {
        val at = AccessTransformerMinecraftTransformer.DefaultTransformer(project, provider)
        at.action()
        patchers.add(at)
    }

    override fun craftBukkit(action: CraftbukkitPatcher.() -> Unit) {
        val cb = CraftbukkitMinecraftTransformer(project, provider)
        cb.action()
        patchers.add(cb)
    }

    override fun spigot(action: SpigotPatcher.() -> Unit) {
        val spigot = SpigotMinecraftTransformer(project, provider)
        spigot.action()
        patchers.add(spigot)
    }

    override fun rift(action: RiftPatcher.() -> Unit) {
        val rift = RiftMinecraftTransformer(project, provider)
        rift.action()
        patchers.add(rift)
    }

    override fun liteloader(action: LiteLoaderPatcher.() -> Unit) {
        val liteloader = LiteLoaderMinecraftTransformer(project, provider)
        liteloader.action()
        patchers.add(liteloader)
    }

    override fun paper(action: PaperPatcher.() -> Unit) {
        val paper = PaperMinecraftTransformer(project, provider)
        paper.action()
        patchers.add(paper)
    }

    @ApiStatus.Experimental
    override fun <T : MinecraftPatcher> customPatcher(mcPatcher: T, action: T.() -> Unit) {
        mcPatcher.action()
        patchers.add(mcPatcher as AbstractMinecraftTransformer)
    }

    override fun customLibraryFilter(filter: (String) -> String?) {
        customLibraryFilter = { l -> filter(l.name)?.let { l.copy(name = it) } }
    }

    override fun libraryFilter(library: Library): Library? {
        return if (customLibraryFilter != null) {
            customLibraryFilter!!(library)
        } else {
            patchers.fold<AbstractMinecraftTransformer, Library?>(library) { acc, patcher -> if (acc != null) patcher.libraryFilter(acc) else null }
        }
    }

}