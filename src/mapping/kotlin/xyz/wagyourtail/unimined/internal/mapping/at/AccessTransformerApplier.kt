package xyz.wagyourtail.unimined.internal.mapping.at

import kotlinx.coroutines.runBlocking
import net.fabricmc.tinyremapper.OutputConsumerPath
import net.fabricmc.tinyremapper.TinyRemapper
import okio.buffer
import okio.sink
import okio.use
import org.gradle.api.logging.Logger
import xyz.wagyourtail.commonskt.reader.CharReader
import xyz.wagyourtail.unimined.mapping.EnvType
import xyz.wagyourtail.unimined.mapping.Namespace
import xyz.wagyourtail.unimined.mapping.formats.at.ATReader
import xyz.wagyourtail.unimined.mapping.formats.at.ATWriter
import xyz.wagyourtail.unimined.mapping.formats.at.LegacyATReader
import xyz.wagyourtail.unimined.mapping.formats.at.LegacyATWriter
import xyz.wagyourtail.unimined.mapping.formats.aw.AWWriter
import xyz.wagyourtail.unimined.mapping.jvms.four.three.three.MethodDescriptor
import xyz.wagyourtail.unimined.mapping.jvms.four.three.two.FieldDescriptor
import xyz.wagyourtail.unimined.mapping.jvms.four.two.one.InternalName
import xyz.wagyourtail.unimined.mapping.tree.AbstractMappingTree
import xyz.wagyourtail.unimined.mapping.tree.MemoryMappingTree
import xyz.wagyourtail.unimined.mapping.visitor.ClassVisitor
import xyz.wagyourtail.unimined.mapping.visitor.FieldVisitor
import xyz.wagyourtail.unimined.mapping.visitor.MappingVisitor
import xyz.wagyourtail.unimined.mapping.visitor.MethodVisitor
import xyz.wagyourtail.unimined.mapping.visitor.delegate.NullDelegator
import xyz.wagyourtail.unimined.mapping.visitor.delegate.delegator
import java.io.*
import java.nio.file.Path
import kotlin.io.path.*

object AccessTransformerApplier {

    fun noAccessMappings(mappings: AbstractMappingTree): AbstractMappingTree {
        val tempMappings = MemoryMappingTree()
        mappings.accept(tempMappings.delegator(object : NullDelegator() {
            override fun visitClass(delegate: MappingVisitor, names: Map<Namespace, InternalName>): ClassVisitor? {
                return default.visitClass(delegate, names)
            }

            override fun visitField(
                delegate: ClassVisitor,
                names: Map<Namespace, Pair<String, FieldDescriptor?>>
            ): FieldVisitor? {
                return default.visitField(delegate, names)
            }

            override fun visitMethod(
                delegate: ClassVisitor,
                names: Map<Namespace, Pair<String, MethodDescriptor?>>
            ): MethodVisitor? {
                return default.visitMethod(delegate, names)
            }

        }))
        return tempMappings
    }

    fun at2aw(at: Path, output: Path, namespace: String, mappings: AbstractMappingTree, isLegacy: Boolean, logger: Logger): Path {
        val temp = noAccessMappings(mappings)
        runBlocking {
            if (isLegacy) {
                LegacyATReader.read(at.readText(), temp, envType = EnvType.JOINED, mapOf("source" to namespace))
            } else {
                ATReader.read(at.readText(), temp, envType = EnvType.JOINED, mapOf("source" to namespace))
            }
        }
        output.sink().buffer().use {
            temp.accept(AWWriter.write(it), listOf(Namespace(namespace)))
        }
        return output
    }


    class AtRemapper(
        val logger: Logger,
        val fromNamespace: Namespace,
        val toNamespace: Namespace,
        val isLegacy: Boolean = false,
        val atPaths: List<String> = emptyList(),
        val mappings: AbstractMappingTree,
    ): OutputConsumerPath.ResourceRemapper {

        override fun canTransform(remapper: TinyRemapper, relativePath: Path): Boolean {
            return relativePath.name == "accesstransformer.cfg" ||
                    relativePath.name.endsWith("_at.cfg") || atPaths.contains(relativePath.name)
        }

        override fun transform(
            destinationDirectory: Path,
            relativePath: Path,
            input: InputStream,
            remapper: TinyRemapper
        ) {
            val output = destinationDirectory.resolve(relativePath)
            output.parent.createDirectories()
            val temp = noAccessMappings(mappings)
            val data = runBlocking {
                if (isLegacy) {
                    LegacyATReader.readData(CharReader(input.readBytes().decodeToString()), true)
                } else {
                    ATReader.readData(CharReader(input.readBytes().decodeToString()), true)
                }
            }
            val remapped = ATWriter.remapMappings(data, temp, fromNamespace, toNamespace)
            output.outputStream().bufferedWriter().use {
                if (isLegacy) {
                    LegacyATWriter.writeData(remapped, it::append)
                } else {
                    ATWriter.writeData(remapped, it::append)
                }
            }

        }
    }
}