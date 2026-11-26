package xyz.wagyourtail.unimined.minecraft.spi

import java.nio.file.Path

/**
 * Serializable descriptor of a per-side jar patch pass run *before*
 * the client/server merge — [ArtifactProductionRequest.preMerge]
 * hooks. Loader transform plugins contribute these through
 * `MinecraftEnvironment.sideJarPatches`; [ProductArtifactTransform]
 * resolves [type] against [SideJarPatchProcessor] ServiceLoader
 * implementations and invokes the processor once per side.
 *
 * Plain value fields only — this object serializes into transform
 * parameters.
 */
data class SideJarPatchSpec(
    /** [SideJarPatchProcessor.type] key — e.g. `forge-binpatch-pack`. */
    val type: String,
    /** Processor-defined arguments (jar paths, pack entry names, …). */
    val args: Map<String, String> = emptyMap()
) : java.io.Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

/**
 * ServiceLoader contract for pre-merge side-jar patching. Patches
 * authored against a side's *clean* jar (FG2-era `binpatch/client` /
 * `binpatch/server` sets) must run on that jar — post-merge the
 * shared classes are member-merged unions whose checksums no longer
 * match any patch input, and marker stamping happens at merge time
 * regardless.
 *
 * Implementations live in transform plugins (e.g. forge's
 * `binpatches.pack` handler) and register under
 * `META-INF/services/xyz.wagyourtail.unimined.minecraft.spi.SideJarPatchProcessor`.
 */
interface SideJarPatchProcessor {

    /** [SideJarPatchSpec.type] key this implementation claims. */
    val type: String

    /**
     * Patch [input] (the clean [side] jar) → returned path.
     * [workDir] is a per-transform scratch dir the processor may use
     * for unpacked pack caches; the result is consumed within the
     * same transform invocation.
     */
    fun patch(
        side: String,
        input: Path,
        args: Map<String, String>,
        workDir: Path
    ): Path
}
