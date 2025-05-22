package xyz.wagyourtail.unimined.internal.minecraft.patch.conversion.reindev

import org.gradle.api.Project
import xyz.wagyourtail.unimined.api.unimined
import xyz.wagyourtail.unimined.internal.minecraft.patch.conversion.AbstractTotalConversionMinecraftTransformer
import xyz.wagyourtail.unimined.util.LazyMutable
import xyz.wagyourtail.unimined.util.compareFlexVer

abstract class AbstractReIndevTransformer(
    project: Project,
    provider: ReIndevProvider,
    providerName: String,
) : AbstractTotalConversionMinecraftTransformer(project, provider, providerName) {

	init {
		project.unimined.fox2codeMaven()
	}

	override var canCombine: Boolean by LazyMutable {
		provider.version.compareFlexVer("2.9") >= 0
	}

}
