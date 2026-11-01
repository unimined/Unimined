package xyz.wagyourtail.unimined.transform.jarmod

import org.gradle.api.logging.Logger
import org.gradle.api.logging.Logging
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import xyz.wagyourtail.commonskt.utils.toHex
import xyz.wagyourtail.unimined.api.mapping.MappingData
import xyz.wagyourtail.unimined.transform.jarmod.internal.fg1.FMLChecksumPatchProcessor
import xyz.wagyourtail.unimined.transform.processor.JarProcessor
import xyz.wagyourtail.unimined.transform.processor.JarProcessor.ModJarInfo
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class FMLChecksumPatchProcessorTest {

    @TempDir
    lateinit var dir: Path

    private val ctx = object : JarProcessor.ProcessorContext {
        override val logger = xyz.wagyourtail.unimined.api.util.uniminedLogger(
            Logging.getLogger(FMLChecksumPatchProcessorTest::class.java))
        override val mappings: MappingData? = null
        override fun mods(): List<ModJarInfo> = emptyList()
    }

    /** `cpw/mods/fml/relauncher/CoreFMLLibraries` shape: parallel
     *  libraries[]/checksums[] String-array fills in <clinit>. */
    private fun coreFMLLibraries(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V1_6, Opcodes.ACC_PUBLIC,
            "cpw/mods/fml/relauncher/CoreFMLLibraries", null,
            "java/lang/Object", null)
        cw.visitField(Opcodes.ACC_PRIVATE + Opcodes.ACC_STATIC,
            "libraries", "[Ljava/lang/String;", null, null)
        cw.visitField(Opcodes.ACC_PRIVATE + Opcodes.ACC_STATIC,
            "checksums", "[Ljava/lang/String;", null, null)
        cw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null).apply {
            visitCode()
            fun arr(field: String, vals: List<String>) {
                visitInsn(Opcodes.ICONST_0 + vals.size)
                visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/String")
                vals.forEachIndexed { i, v ->
                    visitInsn(Opcodes.DUP)
                    visitInsn(Opcodes.ICONST_0 + i)
                    visitLdcInsn(v)
                    visitInsn(Opcodes.AASTORE)
                }
                visitFieldInsn(Opcodes.PUTSTATIC,
                    "cpw/mods/fml/relauncher/CoreFMLLibraries", field,
                    "[Ljava/lang/String;")
            }
            arr("libraries", listOf("asm-all-4.0.jar", "guava-12.0.1.jar"))
            arr("checksums", listOf("deadbeef" + "0".repeat(32), "cafe" + "0".repeat(36)))
            visitInsn(Opcodes.RETURN)
            visitMaxs(6, 0)
            visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    @Test
    fun `checksums are rewritten to shipped artifact sha1s`() {
        val artifact = dir.resolve("asm-all-5.2.jar")
        Files.write(artifact, "fake asm 5.2 content".toByteArray())
        val jar = dir.resolve("merged.jar")
        ZipOutputStream(Files.newOutputStream(jar)).use { o ->
            o.putNextEntry(ZipEntry("cpw/mods/fml/relauncher/CoreFMLLibraries.class"))
            o.write(coreFMLLibraries())
            o.closeEntry()
        }
        val proc = FMLChecksumPatchProcessor {
            mapOf("asm-all-4.0.jar" to artifact.toFile())
        }
        val spec = proc.buildSpec(object : JarProcessor.SpecContext {
            override val productionNamespace: String = "production"
            override fun mods(): List<ModJarInfo> = emptyList()
        })
        proc.processJar(jar, spec, ctx)
        ZipFile(jar.toFile()).use { z ->
            // per-env sums ship inside the jar for the spliced helper
            val sumsText = z.getInputStream(
                z.getEntry("unimined/fg1-lib-sums.txt")
            ).readBytes().decodeToString()
            val expect = java.security.MessageDigest.getInstance("SHA-1")
                .digest("fake asm 5.2 content".toByteArray())
                .toHex()
            assertTrue(
                "asm-all-4.0.jar=$expect" in sumsText.split("\n"),
                "sums resource wrong: $sumsText"
            )
            // helper spliced for the injected call
            assertNotNull(z.getEntry(
                "xyz/wagyourtail/unimined/transform/jarmod/internal/fg1/rt/UniminedFg1ChecksumFix.class"
            ))
            val cn = ClassNode()
            ClassReader(z.getInputStream(
                z.getEntry("cpw/mods/fml/relauncher/CoreFMLLibraries.class")
            ).readBytes()).accept(cn, 0)
            val clinit = cn.methods.first { it.name == "<clinit>" }
            // CInject copies the transformer method into the class and
            // injects a call to the copy — the helper invoke lives in
            // the copied method's body, not in clinit itself
            val callsFix = cn.methods.any { m ->
                m.instructions.toArray().filterIsInstance<MethodInsnNode>().any {
                    it.name == "apply" &&
                        it.owner == "xyz/wagyourtail/unimined/transform/jarmod/internal/fg1/rt/UniminedFg1ChecksumFix"
                }
            }
            assertTrue(callsFix, "no injected checksum-fix call anywhere in the class")
            // and clinit must invoke the copied transformer method
            val clinitCalls = clinit.instructions.toArray()
                .filterIsInstance<MethodInsnNode>()
                .any { it.owner == "cpw/mods/fml/relauncher/CoreFMLLibraries" }
            assertTrue(clinitCalls, "clinit does not call the injected method")
            // original checksums untouched at build time — the helper
            // rewrites them when FML loads the class
            val ldcs = clinit.instructions.toArray()
                .filterIsInstance<LdcInsnNode>().map { it.cst as String }
            assertTrue("deadbeef" + "0".repeat(32) in ldcs)
            assertTrue("cafe" + "0".repeat(36) in ldcs)
        }
    }
}
