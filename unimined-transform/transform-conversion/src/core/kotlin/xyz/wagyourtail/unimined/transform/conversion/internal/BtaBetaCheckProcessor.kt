package xyz.wagyourtail.unimined.transform.conversion.internal

import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import xyz.wagyourtail.unimined.transform.processor.JarProcessor
import java.io.Serializable
import java.nio.file.FileSystems
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * BTA is distributed deobfuscated (`net.minecraft.*`, Identity
 * namespace) but still probes for an *obfuscated* `b1.7.3` base class
 * to decide whether the base game is present — `net.minecraft.client.
 * Minecraft` does `Class.forName("a")` and, on `ClassNotFoundException`,
 * swaps in `net.minecraft.client.gui.error.ScreenMissingMinecraft`
 * ("Minecraft Beta 1.7.3 is Missing!"). The dist *is* the complete
 * game, so the probe is vestigial — a stub `a` class in the default
 * package satisfies it without dragging the real b1.7.3 jar onto the
 * classpath.
 *
 * Self-scoping: [processJar] no-ops unless the jar actually carries
 * `ScreenMissingMinecraft`, so reindev/nsss jars are untouched.
 */
class BtaBetaCheckProcessor : JarProcessor {

    override val name = "bta-beta-check-stub"
    // the merged/stripped dist jar — no prod->named remap on
    // conversion envs, so ordering doesn't matter
    override val preRemap = false

    class Spec : JarProcessor.JarProcessorSpec, Serializable

    override fun buildSpec(context: JarProcessor.SpecContext): JarProcessor.JarProcessorSpec = Spec()
    override fun serializeSpec(spec: JarProcessor.JarProcessorSpec): String = ""
    override fun deserializeSpec(payload: String): JarProcessor.JarProcessorSpec = Spec()

    override fun processJar(
        jar: Path,
        spec: JarProcessor.JarProcessorSpec,
        context: JarProcessor.ProcessorContext
    ) {
        FileSystems.newFileSystem(jar).use { fs ->
            if (!java.nio.file.Files.exists(
                    fs.getPath("net/minecraft/client/gui/error/ScreenMissingMinecraft.class")
                )
            ) return
            val a = fs.getPath("a.class")
            if (java.nio.file.Files.exists(a)) return
            java.nio.file.Files.write(
                a, stubAClass(), StandardOpenOption.CREATE
            )
        }
    }

    /** A minimal public class `a` — `Class.forName("a")` needs a
     *  loadable class, nothing more. */
    private fun stubAClass(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(
            Opcodes.V1_8, Opcodes.ACC_PUBLIC, "a", null,
            "java/lang/Object", null
        )
        cw.visitEnd()
        return cw.toByteArray()
    }
}
