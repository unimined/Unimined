package xyz.wagyourtail.unimined.transform.jarmod.internal.fg1

import org.objectweb.asm.ClassReader
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldInsnNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.TypeInsnNode
import xyz.wagyourtail.commonskt.compress.CONSTANT_TIME_FOR_ZIP_ENTRIES
import xyz.wagyourtail.commonskt.compress.putEntryWithDirs
import xyz.wagyourtail.unimined.transform.processor.JarProcessor
import java.io.InputStream
import java.io.Serializable
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Port of 1.x `FG1MinecraftTransformer.fixForge`: FG1-era FML lists
 * "dynamic" libraries in `CoreFMLLibraries` that it downloads at
 * runtime — when unimined resolves them as real deps, stale copies
 * embedded in the merged jar conflict. If the dyn-lib list mentions
 * argo/bcprov, strip `argo/`/`org/bouncycastle/` from the jar.
 *
 * The dyn-lib scan happens at [processJar] time on the merged jar
 * (like 1.x); the spec carries only enablement.
 */
internal class FG1FixForgeProcessor : JarProcessor {

    override val name = "fg1-fix-forge"
    override val preRemap = true

    class Spec(val enabled: Boolean) : JarProcessor.JarProcessorSpec, Serializable

    override fun buildSpec(context: JarProcessor.SpecContext) = Spec(true)

    override fun serializeSpec(spec: JarProcessor.JarProcessorSpec) =
        (spec as Spec).enabled.toString()

    override fun deserializeSpec(payload: String) = Spec(payload.toBoolean())

    override fun processJar(
        jar: Path,
        spec: JarProcessor.JarProcessorSpec,
        context: JarProcessor.ProcessorContext
    ) {
        if (!(spec as Spec).enabled) return
        ZipFile(jar.toFile()).use { zip ->
            val entry = zip.getEntry("cpw/mods/fml/relauncher/CoreFMLLibraries.class")
                ?: return
            val dyn = zip.getInputStream(entry).use(::dynLibs)
            val strips = buildSet {
                if (dyn.any { it.contains("argo") }) add("argo/")
                if (dyn.any { it.contains("bcprov") }) add("org/bouncycastle/")
            }
            if (strips.isEmpty()) return
            val tmp = Files.createTempFile(jar.parent, "fg1-fix", ".jar")
            try {
                val emitted = mutableSetOf<String>()
                ZipOutputStream(Files.newOutputStream(tmp)).use { out ->
                    zip.entries().asSequence().filter { !it.isDirectory }.forEach { e ->
                        if (strips.any { e.name.startsWith(it) }) return@forEach
                        val name = e.name.replace('\\', '/')
                        // FML-era ATs join class and member with `.`
                        // (`als.a(Lals;)I`); normalize to whitespace
                        // separation so the AT reader accepts them
                        out.putEntryWithDirs(name, emitted,
                            CONSTANT_TIME_FOR_ZIP_ENTRIES) { o ->
                            zip.getInputStream(e).use { it.copyTo(o) }
                        }
                    }
                }
                Files.move(tmp, jar, StandardCopyOption.REPLACE_EXISTING)
            } finally {
                Files.deleteIfExists(tmp)
            }
        }
    }

    /**
     * Read the dyn-lib name set out of `CoreFMLLibraries.<clinit>` —
     * String-array LDCs stored into `libraries` (1.x `getDynLibs`).
     */
    private fun dynLibs(stream: InputStream): Set<String> {
        val cn = ClassNode().also { ClassReader(stream).accept(it, 0) }
        val clInit = cn.methods.firstOrNull { it.name == "<clinit>" } ?: return emptySet()
        val out = mutableSetOf<String>()
        var inArray = false
        val it = clInit.instructions.iterator()
        while (it.hasNext()) {
            val insn = it.next()
            if (insn is TypeInsnNode && insn.opcode == Opcodes.ANEWARRAY
                && insn.desc == "java/lang/String") inArray = true
            if (inArray && insn is LdcInsnNode && insn.cst is String)
                out.add(insn.cst as String)
            if (insn is FieldInsnNode && insn.opcode == Opcodes.PUTSTATIC) {
                if (insn.name == "libraries") break
                inArray = false; out.clear()
            }
        }
        return out
    }

}
