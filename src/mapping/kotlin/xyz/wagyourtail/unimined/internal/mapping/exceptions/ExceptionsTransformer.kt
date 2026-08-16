package xyz.wagyourtail.unimined.internal.mapping.exceptions

import kotlinx.coroutines.runBlocking
import org.gradle.api.logging.Logger
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import xyz.wagyourtail.unimined.internal.mapping.MappingsProvider
import xyz.wagyourtail.unimined.mapping.Namespace
import xyz.wagyourtail.unimined.mapping.jvms.ext.FieldOrMethodDescriptor
import xyz.wagyourtail.unimined.mapping.tree.AbstractMappingTree
import xyz.wagyourtail.unimined.mapping.tree.node._class.member.method.ExceptionNode
import xyz.wagyourtail.unimined.mapping.visitor.ExceptionType
import xyz.wagyourtail.unimined.mapping.visitor.MethodVisitor
import xyz.wagyourtail.unimined.util.DefaultMap
import xyz.wagyourtail.unimined.util.defaultedMapOf
import xyz.wagyourtail.unimined.util.openZipFileSystem
import java.nio.file.FileSystem
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import kotlin.io.path.inputStream

object ExceptionsTransformer {
    fun transform(
        collected: Map<String, Map<String, Pair<FieldOrMethodDescriptor, List<ExceptionNode<MethodVisitor>>>>>,
        fs: FileSystem
    ) {
        for ((className, targetMethods) in collected) {
            val targetClass = "/$className.class"
            val targetPath = fs.getPath(targetClass)

            if (Files.exists(targetPath)) {
                val reader = ClassReader(targetPath.inputStream())
                val writer = ClassWriter(0)

                val node = ClassNode()
                reader.accept(node, 0)

                for (methodNode in node.methods) {
                    val methodInfos = targetMethods[methodNode.name]

                    if (methodInfos != null && methodNode.desc == methodInfos.first.value) {
                        for (exceptionInfo in methodInfos.second) {
                            if (exceptionInfo.type == ExceptionType.ADD) {
                                if (!methodNode.exceptions.contains(exceptionInfo.exception.value))
                                    methodNode.exceptions.add(exceptionInfo.exception.value)
                            } else {
                                methodNode.exceptions.remove(exceptionInfo.exception.value)
                            }
                        }
                    }
                }

                node.accept(writer)
                Files.write(
                    targetPath,
                    writer.toByteArray(),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING
                )
            }
        }
    }

    fun collectExceptions(ns: Namespace, mappingsProvider: MappingsProvider): Map<String, Map<String, Pair<FieldOrMethodDescriptor, List<ExceptionNode<MethodVisitor>>>>> =
        runBlocking {
            val collected = defaultedMapOf<String, MutableMap<String, Pair<FieldOrMethodDescriptor, List<ExceptionNode<MethodVisitor>>>>> {
                mutableMapOf()
            }

            val mappingsTree = mappingsProvider.resolve()

            for (classNode in mappingsTree.classesIter()) {
                val className = classNode.first[ns]?.value ?: continue

                for (methodNode in classNode.second().methods.resolve()) {
                    val methodName = methodNode.names[ns] ?: continue
                    val originalMethodDesc = methodNode.descs.entries.first()
                    val methodDesc = if (ns == originalMethodDesc.key) {
                        originalMethodDesc.value
                    } else {
                        mappingsTree.map(originalMethodDesc.key, ns, originalMethodDesc.value)
                    }

                    val exceptions = methodNode.exceptions.filter { it.namespaces.contains(ns) || it.baseNs == ns }

                    if (exceptions.isNotEmpty()) {
                        collected[className][methodName] = methodDesc to exceptions
                    }
                }
            }

            return@runBlocking collected
        }
}
