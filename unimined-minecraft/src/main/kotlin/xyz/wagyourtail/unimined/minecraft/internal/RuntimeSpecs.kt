package xyz.wagyourtail.unimined.minecraft.internal

import xyz.wagyourtail.unimined.api.spec.LoaderSpec
import xyz.wagyourtail.unimined.api.spec.MinecraftSpec
import xyz.wagyourtail.unimined.api.spec.MinecraftVersionSpec
import xyz.wagyourtail.unimined.api.spec.Side

/** Execution-time [MinecraftSpec] assembled from extension DSL state. */
internal class RuntimeMinecraftSpec(
    override val environmentId: String,
    override val sourceSetName: String?,
    override val version: MinecraftVersionSpec,
    override val side: Side,
    override val loader: LoaderSpec?
) : MinecraftSpec

internal class RuntimeVersionSpec(
    override val version: String
) : MinecraftVersionSpec

internal class RuntimeLoaderSpec(
    override val name: String,
    override val version: String
) : LoaderSpec
