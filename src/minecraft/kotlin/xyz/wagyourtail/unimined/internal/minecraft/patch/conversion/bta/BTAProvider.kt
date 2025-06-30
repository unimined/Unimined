package xyz.wagyourtail.unimined.internal.minecraft.patch.conversion.bta

import org.gradle.api.Project
import org.gradle.api.tasks.SourceSet
import xyz.wagyourtail.unimined.api.minecraft.patch.MinecraftPatcher
import xyz.wagyourtail.unimined.api.minecraft.patch.fabric.FabricLikePatcher
import xyz.wagyourtail.unimined.api.minecraft.patch.fabric.LegacyFabricPatcher
import xyz.wagyourtail.unimined.internal.minecraft.patch.conversion.AbstractTotalConversionMinecraftProvider
import xyz.wagyourtail.unimined.mapping.Namespace
import xyz.wagyourtail.unimined.util.FinalizeOnRead
import xyz.wagyourtail.unimined.util.FinalizeOnWrite
import xyz.wagyourtail.unimined.util.LazyMutable
import xyz.wagyourtail.unimined.util.compareFlexVer

class BTAProvider(project: Project, sourceSet: SourceSet) : AbstractTotalConversionMinecraftProvider(project, sourceSet) {
	override var canCombine: Boolean by FinalizeOnRead(LazyMutable {
		// TODO: Which exact version?
		version.compareFlexVer("7.3") >= 0
	})

	override val includeGlobs: List<String>
		get() = super.includeGlobs + listOf(
			"com/b100/**",
			"com/mojang/**",
			"net/betterthanadventure/**"
		)

	/**
	 * the bta version channel to use
	 */
	var channel: String by FinalizeOnRead("release")

	override val minecraftData = BTADownloader(project, this)

	override val obfuscated: Boolean = false

	override var mcPatcher: MinecraftPatcher by FinalizeOnRead(
		FinalizeOnWrite(
			NoTransformBTATransformer(
				project,
				this
			)
		)
	)

	init {
		mappings.devNamespace = Namespace("official")
		replaceLibraryVersion("org.lwjgl.lwjgl", version = "2.9.4+legacyfabric.10")
	}

	override val mavenGroup: String = "net.betterthanadventure"

	override val minecraftDepName: String by lazy {
		project.path.replace(":", "_").let { projectPath ->
			"bta${if (projectPath == "_") "" else projectPath}${if (sourceSet.name == "main") "" else "+" + sourceSet.name}"
		}
	}

	/**
	 * the bta version channel to use
	 */
	@Suppress("UNUSED")
	fun channel(channel: String) {
		project.logger.info("setting bta version channel to $channel")
		this.channel = channel
	}

	override fun fabric(action: FabricLikePatcher.() -> Unit) {
		super.fabric(action)
//		(this.mcPatcher as FabricLikePatcher).customGameProvider = true
	}

	override fun legacyFabric(action: LegacyFabricPatcher.() -> Unit) {
		super.legacyFabric(action)
//		(this.mcPatcher as LegacyFabricPatcher).customGameProvider = true
	}

	@Deprecated("Ornithe is not required for BTA.", replaceWith = ReplaceWith("legacyFabric(action)"))
	override fun ornitheFabric(action: LegacyFabricPatcher.() -> Unit) {
		this.legacyFabric(action)
	}
}
