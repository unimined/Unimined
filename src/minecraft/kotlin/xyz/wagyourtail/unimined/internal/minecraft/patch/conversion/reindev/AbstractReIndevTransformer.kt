package xyz.wagyourtail.unimined.internal.minecraft.patch.conversion.reindev

import org.gradle.api.Project
import xyz.wagyourtail.unimined.api.unimined
import xyz.wagyourtail.unimined.internal.minecraft.patch.conversion.AbstractTotalConversionMinecraftTransformer

abstract class AbstractReIndevTransformer(
    project: Project,
    provider: ReIndevProvider,
    providerName: String,
) : AbstractTotalConversionMinecraftTransformer(project, provider, providerName) {
	init {
		project.unimined.fox2codeMaven()
	}

	override val includeGlobs: List<String> = listOf(
		"*",
		"META-INF/**",
		"com.fox2code/**",
		"net/silveros/**",
		"net/minecraft/**",
		"com/mojang/**",
		"paulscode/sound/**",
		"com/jcraft/**"
	)
}
