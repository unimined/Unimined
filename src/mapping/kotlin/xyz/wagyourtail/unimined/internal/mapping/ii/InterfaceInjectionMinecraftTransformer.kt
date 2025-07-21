package xyz.wagyourtail.unimined.internal.mapping.ii

import org.gradle.api.logging.Logger
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import xyz.wagyourtail.unimined.util.openZipFileSystem
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import kotlin.io.path.inputStream

object InterfaceInjectionMinecraftTransformer {
    fun transform(
        injections: Map<String, List<String>>,
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

                            val node = ClassNode(Opcodes.ASM9)
                            reader.accept(node, 0)

                            if (node.interfaces == null) {
                                node.interfaces = arrayListOf()
                            }

                            val interfacesWithSignature = mutableMapOf<String, String>()
                            for (injected in injections[target]!!) {
                                val injectedName = injected.substringBefore('<')

                                if (!node.interfaces.contains(injectedName)) {
                                    node.interfaces.add(injectedName)
                                    if (injectedName.length != injected.length) {
                                        interfacesWithSignature[injectedName] = injected
                                    }
                                }
                            }

                            if (node.signature != null) {
                                val resultingSignature = StringBuilder(node.signature)

                                for (injected in injections[target]!!) {
                                    val computedSignature = "L" + injected.replace(".", "/") + ";"

                                    if (!resultingSignature.contains(computedSignature)) resultingSignature.append(computedSignature)
                                }

                                node.signature = resultingSignature.toString()
                            } else if (interfacesWithSignature.isNotEmpty()) {
                                node.signature = buildString {
                                    append('L').append(node.superName).append(';')
                                    for (itf in node.interfaces) {
                                        append('L').append(interfacesWithSignature[itf] ?: itf).append(';')
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
}