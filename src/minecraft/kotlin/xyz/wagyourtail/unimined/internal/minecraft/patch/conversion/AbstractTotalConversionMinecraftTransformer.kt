package xyz.wagyourtail.unimined.internal.minecraft.patch.conversion

import org.gradle.api.Project
import xyz.wagyourtail.unimined.internal.minecraft.patch.AbstractMinecraftTransformer

abstract class AbstractTotalConversionMinecraftTransformer(
	project: Project,
	provider: AbstractTotalConversionMinecraftProvider,
	providerName: String,
) : AbstractMinecraftTransformer(project, provider, providerName)
