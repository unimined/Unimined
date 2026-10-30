package xyz.wagyourtail.unimined.minecraft.internal

import xyz.wagyourtail.unimined.api.spec.LoaderSpec
import xyz.wagyourtail.unimined.api.spec.MinecraftSpec
import xyz.wagyourtail.unimined.api.spec.MinecraftVersionSpec
import xyz.wagyourtail.unimined.api.spec.Side

/** Execution-time [MinecraftSpec] assembled from extension DSL state.
 *  Public-but-`internal`-packaged so provider submodules can read the
 *  runtime-only fields (merge controls) — not part of the spec SPI. */
data class RuntimeMinecraftSpec(
    override val environmentId: String,
    override val sourceSetName: String?,
    override val version: MinecraftVersionSpec,
    override val side: Side,
    override val loader: LoaderSpec?,
    /** Merge strip controls mirrored from the environment's DSL —
     *  the eager (`Provider`) merge path honors glob/flag state even
     *  though it cannot resolve `minecraftLibraries` (ProviderContext
     *  has no configuration access). */
    val mergeIncludeGlobs: List<String> = emptyList(),
    val mergeExcludeGlobs: List<String> = emptyList(),
    val stripBundledServerLibraries: Boolean = true,
    override val librarySubstitutions: List<Pair<String, String>> = emptyList(),
    override val manifestUrls: List<String> = emptyList(),
    override val libraryOverlays: List<String> = emptyList(),
    override val obfuscatedLibraries: List<String> = emptyList()
) : MinecraftSpec

internal class RuntimeVersionSpec(
    override val version: String
) : MinecraftVersionSpec

internal class RuntimeLoaderSpec(
    override val name: String,
    override val version: String
) : LoaderSpec
