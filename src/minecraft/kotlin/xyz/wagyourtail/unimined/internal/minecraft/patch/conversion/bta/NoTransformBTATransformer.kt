package xyz.wagyourtail.unimined.internal.minecraft.patch.conversion.bta

import org.gradle.api.Project

class NoTransformBTATransformer(
	project: Project,
	provider: BTAProvider
): AbstractBTATransformer(
	project,
	provider,
	"Better-Than-Adventure-none"
)
