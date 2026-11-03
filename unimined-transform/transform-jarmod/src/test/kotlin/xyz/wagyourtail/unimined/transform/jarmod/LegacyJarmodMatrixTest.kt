package xyz.wagyourtail.unimined.transform.jarmod

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassReader
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import xyz.wagyourtail.unimined.api.access.AccessModel
import xyz.wagyourtail.unimined.api.mapping.MappingData
import xyz.wagyourtail.unimined.api.mapping.UmlMappingData
import xyz.wagyourtail.unimined.api.spec.RemapSpec
import xyz.wagyourtail.unimined.mapping.MutableMappingTree
import xyz.wagyourtail.unimined.mapping.formats.srg.SrgReader
import xyz.wagyourtail.unimined.remap.RemapStepSpec
import xyz.wagyourtail.unimined.remap.spi.PipelineState
import xyz.wagyourtail.unimined.remap.spi.StepContext
import xyz.wagyourtail.unimined.remapper.uml.UmlRemapStepType
import xyz.wagyourtail.commons.core.logger.JavaLogWrapper
import xyz.wagyourtail.unimined.api.spec.Side
import xyz.wagyourtail.unimined.remap.internal.PipelineRunner
import xyz.wagyourtail.unimined.remap.spi.RemapSurface
import xyz.wagyourtail.unimined.remap.testing.RemapTestHarness
import xyz.wagyourtail.unimined.transform.jarmod.internal.JarModMergeProcessor
import xyz.wagyourtail.unimined.transform.jarmod.internal.modloader.ModLoaderDevFixes
import xyz.wagyourtail.unimined.transform.processor.JarProcessor
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest
import java.util.zip.ZipFile
import kotlin.io.path.exists
import kotlin.io.path.readBytes

/**
 * No-Gradle matrix over the real jarmod pipeline: merge side-selected
 * loader artifacts into the MC jar, run ModLoaderDevFixes, remap
 * official->mcp with the real legacy docs, assert the produced names.
 * Artifacts come from the Gradle module cache — rows skip when absent.
 */
class LegacyJarmodMatrixTest {

    @TempDir
    lateinit var dir: Path

    private val H = RemapTestHarness

    /** First-listed jar wins collisions — mirrors `sideForge + jarMod`. */
    private fun merge(base: Path, vararg inject: Path) {
        val payload = buildString {
            append("false")
            inject.forEach { append('\n').append(it).append(':').append(H.sha1(it)) }
        }
        JarModMergeProcessor().processJar(
            base,
            JarModMergeProcessor().deserializeSpec(payload),
            object : JarProcessor.ProcessorContext {
                override val logger: xyz.wagyourtail.commons.core.logger.Logger = JavaLogWrapper(java.util.logging.Logger.getLogger("matrix"))
                override val mappings: MappingData? = null
                override fun mods() = emptyList<JarProcessor.ModJarInfo>()
            }
        )
    }

    private fun mcpTree(mcpZip: Path, side: String) =
        H.srgTree(mcpZip, side, dir)

    private fun remapCtx(data: MappingData, classpath: List<Path>) =
        H.remapCtx(data, classpath, dir)

    private fun classNode(jar: Path, internal: String) =
        H.classNode(jar, internal)

    /** @return (merged+fixed jar, remapped jar or null when the
     *  bundle has no srg for [side]). */
    private fun runRow(mc: Path, mcpZip: Path, inject: List<Path>,
                       side: String = "client"): Pair<Path, Path?> {
        val merged = Files.createTempFile(dir, "merged-", "-${mc.fileName}")
        Files.copy(mc, merged, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        merge(merged, *inject.toTypedArray())
        FileSystems.newFileSystem(merged).use(ModLoaderDevFixes::fix)
        val tree = mcpTree(mcpZip, side) ?: return merged to null
        val remapped = Files.createTempFile(dir, "remapped-", "-${mc.fileName}")
        // drive the real pipeline — spec resolution, mapping plumbing and
        // step application all go through PipelineRunner like the tasks do
        PipelineRunner.run(
            input = merged,
            output = remapped,
            surface = RemapSurface.JAR,
            stepSpecs = listOf(RemapStepSpec()),
            spec = object : RemapSpec {
                override val sourceNamespace = "official"
                override val targetNamespace = "searge"
                override val classpath: List<Path> = listOf(merged)
            },
            mappings = UmlMappingData(tree),
            classpath = listOf(merged),
            workDir = dir,
            logger = JavaLogWrapper(java.util.logging.Logger.getLogger("matrix")),
            host = "matrix:test ${mc.fileName}",
            envSide = if (side == "server") Side.SERVER else Side.CLIENT
        )
        return merged to remapped
    }

    @Test
    fun `forge-125 client merge+remap keeps client namespace`() {
        val mc = H.mcJar("1.2.5", "client")
            ?: return assumeTrue(false, "no 1.2.5 client jar cached")
        val forge = H.artifact("net.minecraftforge", "forge", "1.2.5-3.4.9.171", "-client.zip")
            ?: return assumeTrue(false, "no forge client zip")
        val modloader = H.artifact("risugami", "modloader", "1.2.5", ".jar")!!
        val mcp = H.artifact("de.oceanlabs.mcp", "mcp_legacy", "6.2-1.2.5", ".zip")!!
        val out = runRow(mc, mcp, listOf(forge, modloader)).second!!
        val ifmt = classNode(out, "net/minecraft/src/IStatStringFormat")
        assertTrue(ifmt != null, "IStatStringFormat missing")
        assertTrue(ifmt!!.access and Opcodes.ACC_INTERFACE != 0,
            "client-side yv must remap to the INTERFACE (server scramble check)")
        assertTrue(classNode(out, "net/minecraft/src/StatStringFormatKeyInv") != null,
            "hk (the original NoClassDefFoundError class) must remap")
    }

    @Test
    fun `forge-125 server merge+remap keeps server namespace`() {
        val mc = H.mcJar("1.2.5", "server")
            ?: return assumeTrue(false, "no 1.2.5 server jar cached")
        val forge = H.artifact("net.minecraftforge", "forge", "1.2.5-3.4.9.171", "-server.zip")
            ?: return assumeTrue(false, "no forge server zip")
        val mlmp = H.artifact("modloadermp", "modloadermp", "1.2.5", "-server.jar")!!
        val mcp = H.artifact("de.oceanlabs.mcp", "mcp_legacy", "6.2-1.2.5", ".zip")!!
        val out = runRow(mc, mcp, listOf(forge, mlmp), "server").second!!
        assertTrue(
            classNode(out, "net/minecraft/src/BlockRedstoneRepeater") != null,
            "server-side yv must remap to BlockRedstoneRepeater")
    }

    @Test
    fun `wrong-side merge scrambles`() {
        // documents why side selection exists: server forge zip into a
        // client jar yields a class where the interface belongs
        val mc = H.mcJar("1.2.5", "client")
            ?: return assumeTrue(false, "no 1.2.5 client jar cached")
        val forge = H.artifact("net.minecraftforge", "forge", "1.2.5-3.4.9.171", "-server.zip")
            ?: return assumeTrue(false, "no forge server zip")
        val mcp = H.artifact("de.oceanlabs.mcp", "mcp_legacy", "6.2-1.2.5", ".zip")!!
        val out = runRow(mc, mcp, listOf(forge)).second!!
        val ifmt = classNode(out, "net/minecraft/src/IStatStringFormat")
        if (ifmt != null) {
            assertFalse(ifmt.access and Opcodes.ACC_INTERFACE != 0,
                "server yv renamed through client mappings should be a class")
        }
    }

    @Test
    fun `modloader matrix merges and fixes`() {
        val rows = listOf(
            "1.1" to "5.6-1.1", "1.2.3" to "6.0-1.2.3",
            "1.2.4" to "6.1-1.2.4", "1.2.5" to "6.2-1.2.5",
            "b1.7.3" to "4.3-b1.7.3", "a1.2.6" to "2.5-a1.2.6",
            "1.0.0" to "1.0.0"
        )
        var ran = 0
        for ((mcV, mcpV) in rows) {
            val mc = H.mcJar(mcV, "client") ?: continue
            val ml = H.artifact("risugami", "modloader", mcV, ".jar") ?: continue
            val mcp = H.artifact("de.oceanlabs.mcp", "mcp_legacy", mcpV, ".zip") ?: continue
            val merged = runRow(mc, mcp, listOf(ml)).first
            val mlClass = classNode(merged, "ModLoader")
            assertTrue(mlClass != null, "$mcV: ModLoader.class missing post-merge")
            assertTrue(
                mlClass!!.methods.any { it.name == "listAllFiles" },
                "$mcV: ModLoaderDevFixes helper missing")
            ran++
        }
        assertTrue(ran > 0, "no matrix rows had cached artifacts")
    }
}
