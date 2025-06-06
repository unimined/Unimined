package xyz.wagyourtail.unimined.internal.minecraft.patch.conversion

import org.gradle.api.Project
import org.gradle.api.tasks.SourceSet
import xyz.wagyourtail.unimined.api.minecraft.patch.fabric.FabricLikePatcher
import xyz.wagyourtail.unimined.api.minecraft.patch.forge.CleanroomPatcher
import xyz.wagyourtail.unimined.api.minecraft.patch.forge.MinecraftForgePatcher
import xyz.wagyourtail.unimined.api.minecraft.patch.forge.NeoForgedPatcher
import xyz.wagyourtail.unimined.internal.minecraft.MinecraftProvider
import xyz.wagyourtail.unimined.util.FinalizeOnRead

abstract class AbstractTotalConversionMinecraftProvider(project: Project, sourceSet: SourceSet) :
	MinecraftProvider(project, sourceSet) {
	/**
	 * The base Minecraft version
	 */
	open val baseVersion: String by FinalizeOnRead(
		"b1.7.3"
	)

	@Deprecated("Minecraft Forge is not supported by this mod")
	override fun minecraftForge(action: MinecraftForgePatcher<*>.() -> Unit) {
	}

	@Deprecated("NeoForge is not supported by this mod")
	override fun neoForge(action: NeoForgedPatcher<*>.() -> Unit) {
	}

	@Deprecated("Flint Loader is not supported by this mod")
	override fun flint(action: FabricLikePatcher.() -> Unit) {
	}

	@Deprecated("Cleanroom is not supported by this mod")
	override fun cleanroom(action: CleanroomPatcher<*>.() -> Unit) {
	}
}
