package xyz.wagyourtail.unimined.internal.minecraft.patch.conversion.reindev

import org.gradle.api.Project

class NoTransformReIndevTransformer(project: Project, provider: ReIndevProvider) : AbstractReIndevTransformer(
	project,
	provider,
	"ReIndev-none"
)
