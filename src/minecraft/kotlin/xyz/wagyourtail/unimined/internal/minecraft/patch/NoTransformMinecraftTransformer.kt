package xyz.wagyourtail.unimined.internal.minecraft.patch

import org.gradle.api.Project
import xyz.wagyourtail.unimined.internal.minecraft.MinecraftProvider

class NoTransformMinecraftTransformer(project: Project, provider: MinecraftProvider): AbstractMinecraftTransformer(
    project,
    provider,
    "minecraft-none"
)