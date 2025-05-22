package xyz.wagyourtail.unimined.internal.minecraft.patch.conversion.bta

import org.gradle.api.Project
import org.gradle.api.tasks.SourceSet
import xyz.wagyourtail.unimined.api.minecraft.patch.MinecraftPatcher
import xyz.wagyourtail.unimined.api.unimined
import xyz.wagyourtail.unimined.internal.minecraft.patch.conversion.TotalConversionMinecraftProvider
import xyz.wagyourtail.unimined.mapping.Namespace
import xyz.wagyourtail.unimined.util.FinalizeOnRead
import xyz.wagyourtail.unimined.util.FinalizeOnWrite
import xyz.wagyourtail.unimined.util.compareFlexVer

class BTAProvider(project: Project, sourceSet: SourceSet) : TotalConversionMinecraftProvider(project, sourceSet) {
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

	override fun afterSetVersion() {
		// LWJGL3 is used in version 7.3+
		if (version.compareFlexVer("7.3") < 0) {
			// Required for the following [2.9.4+legacyfabric.8,) dependency
			project.unimined.legacyFabricMaven()

			replaceLibraryVersion("org.ow2.asm", "asm-all", version = "5.2")
			replaceLibraryVersion("org.lwjgl", version = "2.9.4+legacyfabric.8")
			replaceLibraryVersion("net.java.jinput", "jinput", version = "2.0.9")
		}
	}
}
