package xyz.wagyourtail.unimined.internal.minecraft.patch.access

import kotlinx.coroutines.runBlocking
import net.fabricmc.accesswidener.AccessWidenerReader
import okio.buffer
import okio.sink
import okio.source
import org.gradle.api.Project
import xyz.wagyourtail.unimined.api.minecraft.patch.ataw.AccessConvert
import xyz.wagyourtail.unimined.internal.mapping.at.AccessTransformerApplier
import xyz.wagyourtail.unimined.internal.mapping.aw.AccessWidenerApplier
import xyz.wagyourtail.unimined.internal.minecraft.MinecraftProvider
import xyz.wagyourtail.unimined.mapping.Namespace
import xyz.wagyourtail.unimined.mapping.formats.at.ATWriter
import xyz.wagyourtail.unimined.mapping.formats.at.LegacyATWriter
import xyz.wagyourtail.unimined.mapping.formats.aw.AWReader
import xyz.wagyourtail.unimined.mapping.tree.MemoryMappingTree
import xyz.wagyourtail.unimined.util.withSourceSet
import java.io.File

class AccessConvertImpl(val project: Project, val provider: MinecraftProvider) : AccessConvert {

    override fun aw2at(input: String): File = aw2at(File(input))

    override fun aw2at(input: String, output: String) = aw2at(File(input), File(output))

    override fun aw2at(input: File) = aw2at(
        input,
        provider.sourceSet.resources.srcDirs.first().resolve("META-INF/accesstransformer.cfg")
    )

    override fun aw2at(input: File, output: File): File  = runBlocking {
        val mappings = AWReader.read(input.source().buffer())
        output.bufferedWriter().use {
            mappings.accept(ATWriter.write(it))
        }
        output
    }

    override fun aw2atLegacy(input: String): File = aw2atLegacy(File(input))

    override fun aw2atLegacy(input: String, output: String) = aw2atLegacy(File(input), File(output))

    override fun aw2atLegacy(input: File) = aw2atLegacy(
        input,
        provider.sourceSet.resources.srcDirs.first().resolve("META-INF/accesstransformer.cfg")
    )

    override fun aw2atLegacy(input: File, output: File): File = runBlocking {
        val mappings = AWReader.read(input.source().buffer())
        output.bufferedWriter().use {
            mappings.accept(LegacyATWriter.write(it))
        }
        output
    }

    override fun at2aw(input: String, output: String, namespace: String) =
        at2aw(File(input), File(output), namespace)

    override fun at2aw(input: String, output: String) = at2aw(File(input), File(output))
    override fun at2aw(input: String) = at2aw(File(input))
    override fun at2aw(input: File) = at2aw(input, provider.mappings.devNamespace.name)
    override fun at2aw(input: File, namespace: String) = at2aw(
        input,
        provider.sourceSet.resources.srcDirs.first().resolve("${project.name.withSourceSet(provider.sourceSet)}.accesswidener"),
        namespace
    )

    override fun at2aw(input: File, output: File) = at2aw(input, output, provider.mappings.devNamespace)
    override fun at2aw(input: File, output: File, namespace: String): File = at2aw(input, output, Namespace(namespace))
    private fun at2aw(input: File, output: File, namespace: Namespace): File {
        return runBlocking {
            AccessTransformerApplier.at2aw(
                input.toPath(),
                output.toPath(),
                namespace.name,
                provider.mappings.resolve(),
                false,
                project.logger
            ).toFile()
        }
    }

    override fun mergeAws(inputs: List<File>): File {
        return mergeAws(
            inputs,
            provider.mappings.devNamespace.name
        )
    }

    override fun mergeAws(inputs: List<File>, namespace: String): File {
        return mergeAws(
            provider.sourceSet.resources.srcDirs.first().resolve("${project.name.withSourceSet(provider.sourceSet)}.accesswidener"),
            inputs,
            namespace
        )
    }

    override fun mergeAws(output: File, inputs: List<File>): File {
        return mergeAws(output, provider.mappings.devNamespace, inputs)
    }

    override fun mergeAws(output: File, inputs: List<File>, namespace: String): File {
        return mergeAws(output, Namespace(namespace), inputs)
    }

    private fun mergeAws(output: File, namespace: Namespace, inputs: List<File>): File {
        return AccessWidenerApplier.mergeAws(
            inputs.map { it.toPath() },
            output.toPath(),
            namespace,
            provider.mappings
        ).toFile()
    }

    override fun atLegacy2aw(input: String, output: String, namespace: String) =
        atLegacy2aw(File(input), File(output), namespace)

    override fun atLegacy2aw(input: String, output: String) = atLegacy2aw(File(input), File(output))
    override fun atLegacy2aw(input: String) = atLegacy2aw(File(input))
    override fun atLegacy2aw(input: File) = atLegacy2aw(input, provider.mappings.devNamespace.name)
    override fun atLegacy2aw(input: File, namespace: String) = atLegacy2aw(
        input,
        provider.sourceSet.resources.srcDirs.first().resolve("${project.name.withSourceSet(provider.sourceSet)}.accesswidener"),
        namespace
    )

    override fun atLegacy2aw(input: File, output: File) = atLegacy2aw(input, output, provider.mappings.devNamespace)
    override fun atLegacy2aw(input: File, output: File, namespace: String): File = atLegacy2aw(input, output, Namespace(namespace))
    private fun atLegacy2aw(input: File, output: File, namespace: Namespace): File {
        return runBlocking {
            AccessTransformerApplier.at2aw(
                input.toPath(),
                output.toPath(),
                namespace.name,
                provider.mappings.resolve(),
                true,
                project.logger
            ).toFile()
        }
    }
}