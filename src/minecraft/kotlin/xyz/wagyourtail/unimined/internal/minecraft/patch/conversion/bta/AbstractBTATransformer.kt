package xyz.wagyourtail.unimined.internal.minecraft.patch.conversion.bta

import org.gradle.api.Project
import xyz.wagyourtail.unimined.internal.minecraft.patch.conversion.AbstractTotalConversionMinecraftTransformer

abstract class AbstractBTATransformer(
	project: Project,
	provider: BTAProvider,
	providerName: String,
) : AbstractTotalConversionMinecraftTransformer(project, provider, providerName) {
	override val includeGlobs: List<String> = listOf(
		"*",
		"META-INF/**",
		"net/betterthanadventure/**",
		"net/minecraft/**",
		"com/mojang/**",
	)

	override fun shouldStripClass(path: String): Boolean = false
}
