package xyz.wagyourtail.unimined.transform.fabric

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import xyz.wagyourtail.unimined.api.mapping.MappingTree
import xyz.wagyourtail.unimined.api.mapping.MappingVisitor
import xyz.wagyourtail.unimined.transform.fabric.internal.AccessWidener
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import org.junit.jupiter.api.io.TempDir

class AccessWidenerTest {

    @TempDir
    lateinit var dir: Path

    private val identityTree = object : MappingTree {
        override val namespaces = listOf("named")
        override fun accept(sourceNamespace: String, targetNamespace: String, visitor: MappingVisitor) {}
    }

    private val officialToNamed = object : MappingTree {
        override val namespaces = listOf("official", "named")
        override fun accept(sourceNamespace: String, targetNamespace: String, visitor: MappingVisitor) {
            require(sourceNamespace == "official" && targetNamespace == "named")
            visitor.visitClass("a/b/C", "dev/pkg/Clazz")
        }
    }

    @Test
    fun `parses v2 header and directives`() {
        val aw = AccessWidener.parse(
            """
            accessWidener	v2	named
            accessible	class	dev/pkg/Clazz
            accessible	method	dev/pkg/Clazz	doIt	()V
            extendable	field	dev/pkg/Clazz	state	Ljava/lang/String;
            mutable	field	dev/pkg/Clazz	other	I
            """.trimIndent()
        )
        assertEquals("named", aw.namespace)
        assertEquals("dev/pkg/Clazz", aw.owners.single())
    }

    @Test
    fun `accessible class and method become public`() {
        val bytes = writeClass(access = Opcodes.ACC_PRIVATE or 0x200 /*super*/, privateMethod = true)
        val aw = AccessWidener.parse(
            "accessWidener\tv2\tnamed\n" +
                "accessible\tclass\ta/b/C\n" +
                "accessible\tmethod\ta/b/C\thidden\t()V"
        )
        val out = apply(bytes, aw, identityTree, "named")
        assertAccess(out)
    }

    @Test
    fun `directives written in a different namespace are rebased`() {
        val bytes = writeClass(access = Opcodes.ACC_FINAL, privateMethod = false, className = "dev/pkg/Clazz")
        val aw = AccessWidener.parse(
            "accessWidener\tv2\tofficial\n" +
                "extendable\tclass\ta/b/C\n" +
                "accessible\tmethod\ta/b/C\thidden\t()V"
        )
        val out = apply(bytes, aw, officialToNamed, "named")
        val reader = ClassReader(out)
        assertEquals(0, reader.access and Opcodes.ACC_FINAL, "class final flag should be cleared")
        assertEquals(Opcodes.ACC_PUBLIC, reader.access and Opcodes.ACC_PUBLIC)
    }

    @Test
    fun `transitive directives are not applied to the jar`() {
        val bytes = writeClass(access = Opcodes.ACC_PRIVATE or 0x200, privateMethod = false)
        val aw = AccessWidener.parse(
            "accessWidener\tv2\tnamed\n" +
                "transitive-accessible\tclass\ta/b/C"
        )
        val out = apply(bytes, aw, identityTree, "named")
        val reader = ClassReader(out)
        assertEquals(Opcodes.ACC_PRIVATE, reader.access and Opcodes.ACC_PRIVATE)
    }

    @Test
    fun `apply is a no-op without aw files`() = Unit

    private fun apply(
        bytes: ByteArray,
        aw: AccessWidener,
        tree: MappingTree,
        target: String
    ): ByteArray {
        val input = dir.resolve("in-${System.nanoTime()}.jar")
        val output = dir.resolve("out-${System.nanoTime()}.jar")
        JarOutputStream(Files.newOutputStream(input)).use {
            it.putNextEntry(JarEntry("a/b/C.class"))
            it.write(bytes)
            it.closeEntry()
        }
        ApplyAccessWidenerStepType().apply(input, output, listOf(aw), tree, target)
        JarFile(output.toFile()).use { jar ->
            return jar.getInputStream(jar.getJarEntry("a/b/C.class")).readBytes()
        }
    }

    private fun writeClass(
        access: Int,
        privateMethod: Boolean,
        className: String = "a/b/C"
    ): ByteArray {
        val writer = ClassWriter(0)
        writer.visit(
            Opcodes.V17, access, className, null, "java/lang/Object", null
        )
        val methodAccess = if (privateMethod) Opcodes.ACC_PRIVATE else Opcodes.ACC_PROTECTED
        val method = writer.visitMethod(methodAccess, "hidden", "()V", null, null)
        method.visitCode()
        method.visitInsn(Opcodes.RETURN)
        method.visitMaxs(0, 0)
        method.visitEnd()
        writer.visitEnd()
        return writer.toByteArray()
    }

    private fun assertAccess(out: ByteArray) {
        val reader = ClassReader(out)
        assertEquals(
            Opcodes.ACC_PUBLIC or 0x200,
            reader.access and (Opcodes.ACC_PUBLIC or Opcodes.ACC_PRIVATE or Opcodes.ACC_PROTECTED or 0x200)
        )
        reader.accept(object : org.objectweb.asm.ClassVisitor(Opcodes.ASM9) {
            override fun visitMethod(
                access: Int, name: String, descriptor: String,
                signature: String?, exceptions: Array<out String>?
            ): org.objectweb.asm.MethodVisitor {
                if (name == "hidden") {
                    assertEquals(Opcodes.ACC_PUBLIC, access and Opcodes.ACC_PUBLIC)
                }
                return object : org.objectweb.asm.MethodVisitor(Opcodes.ASM9) {}
            }
        }, 0)
    }

}
