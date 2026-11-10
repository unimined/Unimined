package xyz.wagyourtail.unimined.remapper.uml.ext

import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import xyz.wagyourtail.unimined.mapping.MutableMappingTree
import xyz.wagyourtail.unimined.mapping.Namespace
import xyz.wagyourtail.unimined.mapping.element.mapping.MutableClassMapping
import xyz.wagyourtail.unimined.mapping.jvms.four.two.one.InternalName
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

object Fixtures {

    val obf = Namespace("intermediary")
    val named = Namespace("named")

    fun classBytes(name: String, access: Int = Opcodes.ACC_PUBLIC, block: ClassNode.() -> Unit = {}): ByteArray {
        val node = ClassNode().apply {
            this.access = access
            this.name = name
            this.superName = "java/lang/Object"
            this.version = Opcodes.V17
            block()
        }
        val writer = ClassWriter(0)
        node.accept(writer)
        return writer.toByteArray()
    }

    fun writeJar(path: Path, entries: Map<String, ByteArray>, manifest: Manifest? = null) {
        val stream = if (manifest != null) JarOutputStream(Files.newOutputStream(path), manifest)
            else ZipOutputStream(Files.newOutputStream(path))
        stream.use { jos ->
            for ((name, bytes) in entries) {
                jos.putNextEntry(ZipEntry(name))
                jos.write(bytes)
                jos.closeEntry()
            }
        }
    }

    fun readClasses(path: Path): Map<String, ClassNode> {
        val out = mutableMapOf<String, ClassNode>()
        ZipFile(path.toFile()).use { zin ->
            for (entry in zin.entries().asSequence().filter { it.name.endsWith(".class") }) {
                val node = ClassNode()
                org.objectweb.asm.ClassReader(zin.getInputStream(entry)).accept(node, 0)
                out[node.name] = node
            }
        }
        return out
    }

    fun treeOf(vararg namespaces: Namespace = arrayOf(obf, named)): MutableMappingTree =
        MutableMappingTree(namespaces.toSet())

    fun MutableMappingTree.cls(obfName: String, namedName: String): MutableClassMapping =
        clazz(mapOf(obf to InternalName.unchecked(obfName), named to InternalName.unchecked(namedName)))

}
