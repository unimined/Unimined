package xyz.wagyourtail.unimined.transform.forge.internal.binpatch

import xyz.wagyourtail.unimined.minecraft.spi.SideJarPatchProcessor
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.jar.JarFile

/**
 * FG2-era `binpatches.pack.lzma` applied to a clean *side* jar —
 * ServiceLoader impl behind `unimined-minecraft`'s pre-merge
 * [xyz.wagyourtail.unimined.minecraft.spi.SideJarPatchSpec] channel.
 * The universal pack's `binpatch/client` / `binpatch/server` sets
 * are authored against the clean side jars; the merge consumes the
 * patched outputs and stamps side markers over them (1.x
 * `transformIntern` per side → merge).
 *
 * `args`: `loaderJar` — the universal jar carrying the pack;
 * `packEntry` — pack entry name (`binpatches.pack.lzma`).
 */
class BinpatchesPackSideProcessor : SideJarPatchProcessor {

    override val type = "forge-binpatch-pack"

    override fun patch(
        side: String,
        input: Path,
        args: Map<String, String>,
        workDir: Path
    ): Path {
        val loaderJar = Path.of(
            args["loaderJar"] ?: error("forge-binpatch-pack: loaderJar arg required")
        )
        val packEntry = args["packEntry"] ?: "binpatches.pack.lzma"
        Files.createDirectories(workDir)
        // unpack lzma→pack200 once — both side calls share the workDir
        val packBytesFile = workDir.resolve("pack.unpacked")
        val packBytes = if (Files.exists(packBytesFile)) {
            Files.readAllBytes(packBytesFile)
        } else {
            val pack = workDir.resolve("binpatches.pack.lzma")
            JarFile(loaderJar.toFile()).use { jar ->
                val entry = jar.getJarEntry(packEntry)
                    ?: error("$packEntry not found in $loaderJar")
                jar.getInputStream(entry).use {
                    Files.copy(it, pack, StandardCopyOption.REPLACE_EXISTING)
                }
            }
            BinpatchesPackPatcher.unpackPack(pack).also {
                Files.write(packBytesFile, it)
            }
        }
        val patches = BinpatchesPackPatcher.readPatches(packBytes, side)
        val out = workDir.resolve("$side-patched.jar")
        if (!Files.exists(out)) {
            BinpatchesPackPatcher.apply(
                input,
                listOf(
                    BinpatchesPackPatcher.SidePatchInput(side, input, patches)
                ),
                out
            )
        }
        return out
    }
}
