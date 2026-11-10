package xyz.wagyourtail.unimined.adapter.patchbase

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.LdcInsnNode
import xyz.wagyourtail.patchbase.installer.ClassDiff
import xyz.wagyourtail.patchbase.installer.PatchbaseInstaller
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Real bdiff round-trip through the published patchbase installer:
 *  create A→A' delta, patch jar, verify output. */
class PatchbaseInstallerTest {

    @TempDir
    lateinit var dir: Path

    private fun targetClass(constant: Int): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "Target",
            null, "java/lang/Object", null)
        val mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "value", "()I",
            null, null)
        mv.visitCode()
        mv.visitLdcInsn(constant)
        mv.visitInsn(Opcodes.IRETURN)
        mv.visitMaxs(1, 1)
        mv.visitEnd()
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun zip(path: Path, entries: Map<String, ByteArray>) {
        ZipOutputStream(Files.newOutputStream(path)).use { out ->
            entries.forEach { (name, bytes) ->
                out.putNextEntry(ZipEntry(name))
                out.write(bytes)
                out.closeEntry()
            }
        }
    }

    private fun readEntry(jar: Path, name: String): ByteArray? =
        ZipFile(jar.toFile()).use { z ->
            z.getEntry(name)?.let { z.getInputStream(it).readAllBytes() }
        }

    private fun classConstant(bytes: ByteArray): Int {
        val node = ClassNode()
        ClassReader(bytes).accept(node, 0)
        val mv = node.methods.first { it.name == "value" }
        return (mv.instructions.first { it is LdcInsnNode }
                as LdcInsnNode).cst as Int
    }

    /** Same normalization the create-task + installer both apply to
     *  base class bytes before checksumming (SKIP_DEBUG reserialize). */
    private fun normalize(bytes: ByteArray): ByteArray =
        ClassWriter(ClassReader(bytes), ClassReader.SKIP_DEBUG)
            .toByteArray()

    @Test
    fun `bdiff round-trip yields the dirty class`() {
        val v1 = targetClass(1)
        val v2 = targetClass(2)

        val diff = ClassDiff.create(v1, v2)
        assertTrue(diff.isNotEmpty())

        val patched = ClassDiff.apply(v1, diff)
        assertEquals(2, classConstant(patched))
    }

    @Test
    fun `bdiff entry patches the same-named class`() {
        val v1 = targetClass(1)
        val v2 = targetClass(2)

        val base = dir.resolve("base.jar")
        zip(base, mapOf("Target.class" to v1))
        val patch = dir.resolve("patch.zip")
        zip(patch, mapOf("Target.class.bdiff" to ClassDiff.create(normalize(v1), v2)))
        val out = dir.resolve("out.jar")

        PatchbaseInstaller().patch(patch, base, out)

        assertEquals(2, classConstant(readEntry(out, "Target.class")!!))
    }

    @Test
    fun `binpatch entry patches the same-named class`() {
        val v1 = targetClass(1)
        val v2 = targetClass(2)

        val base = dir.resolve("base.jar")
        zip(base, mapOf("Target.class" to v1))
        val patch = dir.resolve("patch.zip")
        zip(patch, mapOf("Target.class.binpatch" to
            xyz.wagyourtail.patchbase.installer.BinPatch.create(
                "Target", normalize(v1), v2).write()))
        val out = dir.resolve("out.jar")

        PatchbaseInstaller().patch(patch, base, out)

        assertEquals(2, classConstant(readEntry(out, "Target.class")!!))
    }

    @Test
    fun `bdiff against the wrong base fails on the checksum`() {
        val patch = ClassDiff.create(targetClass(1), targetClass(2))
        assertThrows(IllegalStateException::class.java) {
            ClassDiff.apply(targetClass(7), patch)
        }
    }

    @Test
    fun `non-bdiff entries copy and replace verbatim`() {
        val base = dir.resolve("base.jar")
        zip(base, mapOf(
            "keep.txt" to "keep".toByteArray(),
            "replace.txt" to "old".toByteArray(),
        ))
        val patch = dir.resolve("patch.zip")
        zip(patch, mapOf(
            "replace.txt" to "new".toByteArray(),
            "added.txt" to "added".toByteArray(),
        ))
        val out = dir.resolve("out.jar")

        PatchbaseInstaller().patch(patch, base, out)

        assertArrayEquals("keep".toByteArray(), readEntry(out, "keep.txt"))
        assertArrayEquals("new".toByteArray(), readEntry(out, "replace.txt"))
        assertArrayEquals("added".toByteArray(), readEntry(out, "added.txt"))
    }

}
