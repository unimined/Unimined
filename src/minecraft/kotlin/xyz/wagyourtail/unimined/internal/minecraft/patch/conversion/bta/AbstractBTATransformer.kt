package xyz.wagyourtail.unimined.internal.minecraft.patch.conversion.bta

import org.gradle.api.Project
import xyz.wagyourtail.unimined.api.minecraft.MinecraftJar
import xyz.wagyourtail.unimined.internal.minecraft.patch.conversion.AbstractTotalConversionMinecraftTransformer

abstract class AbstractBTATransformer(
	project: Project,
	provider: BTAProvider,
	providerName: String,
) : AbstractTotalConversionMinecraftTransformer(project, provider, providerName) {
	override var canCombine: Boolean = false

	override fun afterRemap(baseMinecraft: MinecraftJar): MinecraftJar = baseMinecraft
}
