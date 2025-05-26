package xyz.wagyourtail.unimined.internal.minecraft.transform.fixes

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import java.nio.file.FileSystem
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.writeBytes

object FixFG2DeobfEnvironment {



    fun fixDeobfEnvironment(fs: FileSystem) {
        val path = fs.getPath("net/minecraftforge/fml/common/launcher/FMLDeobfTweaker.class")
        if (!path.exists()) return


        val reader = path.inputStream().use { ClassReader(it) }

        val writer = ClassWriter(reader, ClassWriter.COMPUTE_MAXS)

        reader.accept(object : ClassVisitor(Opcodes.ASM9, writer) {

            override fun visitMethod(
                access: Int,
                name: String,
                descriptor: String,
                signature: String?,
                exceptions: Array<out String>?,
            ): MethodVisitor? {
                return if (name == "injectIntoClassLoader") {
                    object : MethodVisitor(api, super.visitMethod(access, name, descriptor, signature, exceptions)) {

                        override fun visitLdcInsn(value: Any?) {
                            if (value is String && value.endsWith("DeobfuscationTransformer")) {
                                super.visitInsn(Opcodes.ACONST_NULL)
                            } else {
                                super.visitLdcInsn(value)
                            }
                        }

                    }
                } else {
                    super.visitMethod(access, name, descriptor, signature, exceptions)
                }
            }

        }, 0)

        path.writeBytes(writer.toByteArray())
    }

}