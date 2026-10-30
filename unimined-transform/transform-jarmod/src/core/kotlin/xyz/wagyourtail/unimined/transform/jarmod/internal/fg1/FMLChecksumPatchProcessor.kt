package xyz.wagyourtail.unimined.transform.jarmod.internal.fg1

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldInsnNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.TypeInsnNode
import xyz.wagyourtail.commonskt.compress.CONSTANT_TIME_FOR_ZIP_ENTRIES
import xyz.wagyourtail.commonskt.compress.putEntryWithDirs
import xyz.wagyourtail.unimined.transform.processor.JarProcessor
import java.io.File
import java.io.Serializable
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Rewrites `CoreFMLLibraries.checksums` so FML's downloader accepts
 * the artifacts we actually ship in `runDir/lib`. The class holds
 * parallel `libraries[]`/`checksums[]` `String` arrays built in
 * `<clinit>`; for every shipped lib whose content differs from the
 * era's original (asm-5.2 substitution, maven-resolved fmllibs) the
 * matching `checksums` LDC is replaced with our artifact's SHA1 —
 * integrity checking stays on, real corruption still fails the hash.
 * Non-LDC slots (e.g. `FMLInjectionData.deobfuscationDataHash` on
 * 1.5.2) keep their expression — only index alignment matters.
 */
internal class FMLChecksumPatchProcessor(
    private val artifacts: () -> Map<String, File> = { emptyMap() }
) : JarProcessor {

    override val name = "fg1-checksum-patch"
    override val preRemap = true

    class Spec(val sums: Map<String, String>) : JarProcessor.JarProcessorSpec, Serializable

    override fun buildSpec(context: JarProcessor.SpecContext) =
        Spec(artifacts().mapValues { sha1(it.value) })

    override fun serializeSpec(spec: JarProcessor.JarProcessorSpec) =
        (spec as Spec).sums.entries.joinToString(";") { "${it.key}=${it.value}" }

    override fun deserializeSpec(payload: String) = Spec(
        if (payload.isEmpty()) emptyMap()
        else payload.split(";").associate { it.substringBefore('=') to it.substringAfter('=') }
    )

    override fun processJar(
        jar: Path,
        spec: JarProcessor.JarProcessorSpec,
        context: JarProcessor.ProcessorContext
    ) {
        val sums = (spec as Spec).sums
        if (sums.isEmpty()) return
        val tmp = Files.createTempFile(jar.parent, "fmlsums", ".jar")
        try {
            val emitted = mutableSetOf<String>()
            ZipFile(jar.toFile()).use { zip ->
                ZipOutputStream(Files.newOutputStream(tmp)).use { out ->
                    zip.entries().asSequence().filter { !it.isDirectory }.forEach { e ->
                        out.putEntryWithDirs(e.name.replace('\\', '/'), emitted,
                            CONSTANT_TIME_FOR_ZIP_ENTRIES) { o ->
                            if (e.name == "cpw/mods/fml/relauncher/CoreFMLLibraries.class") {
                                zip.getInputStream(e).use { o.write(patch(it.readBytes(), sums)) }
                            } else {
                                zip.getInputStream(e).use { it.copyTo(o) }
                            }
                        }
                    }
                }
            }
            Files.move(tmp, jar, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    private fun patch(bytes: ByteArray, sums: Map<String, String>): ByteArray {
        val cn = ClassNode()
        ClassReader(bytes).accept(cn, 0)
        val clInit = cn.methods.firstOrNull { it.name == "<clinit>" } ?: return bytes
        // collect per-array element insns in order (LDC strings or the
        // GETSTATIC placeholder — index alignment is what matters)
        val arrays = mutableMapOf<String, MutableList<LdcInsnNode?>>()
        var fill: MutableList<LdcInsnNode?>? = null
        for (insn in clInit.instructions) {
            if (insn is TypeInsnNode && insn.opcode == Opcodes.ANEWARRAY &&
                insn.desc == "java/lang/String") {
                fill = mutableListOf()
            } else if (fill != null && insn is LdcInsnNode && insn.cst is String) {
                fill.add(insn)
            } else if (fill != null && insn is FieldInsnNode &&
                insn.opcode == Opcodes.GETSTATIC) {
                fill.add(null)
            } else if (insn is FieldInsnNode && insn.opcode == Opcodes.PUTSTATIC &&
                insn.desc == "[Ljava/lang/String;") {
                if (fill != null) arrays[insn.name] = fill
                fill = null
            }
        }
        val libs = arrays["libraries"] ?: return bytes
        val sums0 = arrays["checksums"] ?: return bytes
        var dirty = false
        for (i in libs.indices) {
            val name = (libs[i]?.cst as? String) ?: continue
            val sha = sums[name] ?: continue
            val slot = sums0.getOrNull(i) ?: continue
            if (slot.cst != sha) { slot.cst = sha; dirty = true }
        }
        return if (dirty) ClassWriter(0).also { cn.accept(it) }.toByteArray() else bytes
    }

    private fun sha1(f: File): String {
        val md = MessageDigest.getInstance("SHA-1")
        f.inputStream().use { s ->
            val buf = ByteArray(8192)
            while (true) {
                val n = s.read(buf); if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
