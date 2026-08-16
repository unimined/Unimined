package xyz.wagyourtail.unimined.internal.mapping.ii

import kotlinx.coroutines.runBlocking
import org.gradle.api.logging.Logger
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.tree.ClassNode
import xyz.wagyourtail.unimined.internal.mapping.MappingsProvider
import xyz.wagyourtail.unimined.mapping.Namespace
import xyz.wagyourtail.unimined.mapping.jvms.four.seven.nine.one.`class`.ClassSignature
import xyz.wagyourtail.unimined.mapping.jvms.four.seven.nine.one.reference.ClassTypeSignature
import xyz.wagyourtail.unimined.mapping.visitor.InterfacesType
import xyz.wagyourtail.unimined.util.openZipFileSystem
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import kotlin.io.path.inputStream

object InterfaceInjectionMinecraftTransformer {
    fun transform(
        injections: Map<String, List<Pair<InterfacesType, ClassTypeSignature>>>,
        baseMinecraft: Path,
        output: Path,
        logger: Logger
    ): Boolean {
        if (injections.isNotEmpty()) {
            Files.copy(baseMinecraft, output, StandardCopyOption.REPLACE_EXISTING)
            output.openZipFileSystem(mapOf("mutable" to true)).use { fs ->
                logger.debug("Transforming $output with ${injections.values.sumOf { it.size }} interface injections")

                for (target in injections.keys) {
                    try {
                        val targetClass = "/" + target.replace(".", "/") + ".class"
                        val targetPath = fs.getPath(targetClass)
                        logger.debug("Transforming $targetPath")
                        if (Files.exists(targetPath)) {
                            val reader = ClassReader(targetPath.inputStream())
                            val writer = ClassWriter(0)

                            val node = ClassNode()
                            reader.accept(node, 0)

                            if (node.interfaces == null) {
                                node.interfaces = arrayListOf()
                            }

                            if (node.signature == null) {
                                node.signature = "L${node.superName};"
                            }

                            val signature = ClassSignature.read(node.signature)
                            val signatureParts = signature.getParts()
                            val superInterfaces = signatureParts.third.map { it.toString() }.toMutableList()

                            for (injectionNode in injections[target]!!) {
                                val injectionString = injectionNode.second.toString()
                                val interfaceName = injectionString.substring(1, injectionString.length - 1)
                                if (injectionNode.first == InterfacesType.ADD) {
                                    if (!superInterfaces.contains(injectionString)) {
                                        superInterfaces.add(injectionString)
                                    }
                                    if (!node.interfaces.contains(interfaceName)) {
                                        node.interfaces.add(interfaceName)
                                    }
                                } else {
                                    superInterfaces.remove(injectionString)
                                    node.interfaces.remove(interfaceName)
                                }
                            }

                            node.signature = ClassSignature.create(signatureParts.first?.getParts()?.map { it.value }, signatureParts.second.toString(), superInterfaces).value

                            node.accept(writer)
                            Files.write(
                                targetPath,
                                writer.toByteArray(),
                                StandardOpenOption.CREATE,
                                StandardOpenOption.TRUNCATE_EXISTING
                            )
                        } else {
                            logger.warn("Could not find class $targetClass in $output")
                        }
                    } catch (e: Exception) {
                        logger.warn(
                            "An error occurred while transforming $target with interface injection in $output",
                            e
                        )
                    }
                }
            }


            return true
        }

        return false
    }

    fun collectFromMappings(ns: Namespace, mappingsProvider: MappingsProvider): Map<String, List<Pair<InterfacesType, ClassTypeSignature>>> = runBlocking {
        val injections = mutableMapOf<String, List<Pair<InterfacesType, ClassTypeSignature>>>()

        val mappingsTree = mappingsProvider.resolve()

        for (classNode in mappingsTree.classesIter()) {
            val className = classNode.first[ns]?.value ?: continue

            val interfaces = classNode.second().interfaces.filter { it.namespaces.contains(ns) || it.baseNs == ns }
            injections[className] = interfaces.map {
                it.type to if (it.baseNs == ns) it.name else mappingsTree.map(it.baseNs, ns, it.name)
            }
        }

        return@runBlocking injections
    }
}
