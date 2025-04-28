package xyz.wagyourtail.unimined.internal.minecraft.transform.fixes

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.*
import java.nio.file.FileSystem
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes

object FixInnerClasses {

    fun apply(fs: FileSystem) {
        fs.rootDirectories.forEach { root ->
            Files.walk(root).use { s ->
                for (path in s.filter { it.name.endsWith(".class") }) {
                    val simpleName = path.nameWithoutExtension
                    if (simpleName.contains("$")) continue

                    val children = (path.parent?.listDirectoryEntries()?.filter { it.nameWithoutExtension.startsWith("$simpleName$") } ?: emptyList()).associateBy {
                        it.nameWithoutExtension
                    }.filterKeys { !it.contains(Regex("\\$\\d+")) }

                    val nodes = mutableListOf<InnerClassNode>()

                    if (children.isNotEmpty()) {
                        val parentReader = ClassReader(path.readBytes())
                        val parentNode = ClassNode()
                        parentReader.accept(parentNode, 0)

                        val packageName = parentNode.name.substringBeforeLast("/", "").let {
                            if (it.isEmpty()) {
                                ""
                            } else {
                                "$it/"
                            }
                        }

                        val writers = children.map { (simpleChildName, childFile) ->
                            val childReader = ClassReader(childFile.readBytes())
                            val childNode = ClassNode()
                            childReader.accept(childNode, 0)

                            var simpleParentName = simpleChildName.substringBeforeLast("$")
                            while (simpleParentName !in children && simpleParentName != simpleName) {
                                simpleParentName = simpleParentName.substringBeforeLast("$")
                            }

                            nodes.add(InnerClassNode(
                                childNode.name,
                                packageName + simpleParentName,
                                simpleChildName.removePrefix("$simpleParentName$"),
                                childNode.access or Opcodes.ACC_STATIC
                            ))

                            val childWriter = ClassWriter(childReader, 0)
                            return@map {

                                if (childNode.innerClasses == null) {
                                    childNode.innerClasses = mutableListOf()
                                }
                                val existing = childNode.innerClasses.map { it.name }.toSet()
                                childNode.innerClasses.addAll(nodes.filter { it.name !in existing })

                                childNode.accept(childWriter)
                                childFile.writeBytes(childWriter.toByteArray(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
                            }
                        }

                        val parentWriter = ClassWriter(parentReader, 0)

                        if (parentNode.innerClasses == null) {
                            parentNode.innerClasses = mutableListOf()
                        }
                        val existing = parentNode.innerClasses.map { it.name }.toSet()
                        parentNode.innerClasses.addAll(nodes.filter { it.name !in existing })

                        parentNode.accept(parentWriter)
                        path.writeBytes(parentWriter.toByteArray(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
                        writers.forEach { it.invoke() }
                    }
                }
            }
        }
    }

}