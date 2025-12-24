package xyz.wagyourtail.unimined.internal.source.remapper

import org.gradle.api.Project
import org.gradle.api.artifacts.ExternalModuleDependency
import org.gradle.api.file.FileCollection
import org.gradle.process.JavaExecSpec
import xyz.wagyourtail.unimined.api.mapping.MappingNamespaceTree
import xyz.wagyourtail.unimined.api.mapping.task.ExportMappingsTask
import xyz.wagyourtail.unimined.api.source.remapper.SourceRemapper
import xyz.wagyourtail.unimined.api.unimined
import xyz.wagyourtail.unimined.internal.mapping.MappingsProvider
import xyz.wagyourtail.unimined.internal.mapping.task.ExportMappingsTaskImpl
import xyz.wagyourtail.unimined.internal.source.SourceProvider
import xyz.wagyourtail.unimined.util.execOps
import xyz.wagyourtail.unimined.util.withSourceSet
import java.io.File
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories
import kotlin.io.path.exists

class SourceRemapperImpl(val project: Project, val provider: SourceProvider) : SourceRemapper {

    val sourceRemapper = project.configurations.maybeCreate("sourceRemapper".withSourceSet(provider.minecraft.sourceSet))

    override fun remapper(dep: Any, action: ExternalModuleDependency.() -> Unit) {
        sourceRemapper.dependencies.add(
            project.dependencies.create(
                if (dep is String && !dep.contains(":")) {
                    project.unimined.wagYourMaven("snapshots")
                    "xyz.wagyourtail.unimined:source-remap:$dep"
                } else {
                    dep
                }
            )
            .also {
                action(it as ExternalModuleDependency)
            }
        )
    }

    val tempDir = project.unimined.getLocalCache().resolve("source-remap-cache")


    override fun remap(
        inputOutput: Map<Path, Path>,
        classpath: FileCollection,
        source: MappingNamespaceTree.Namespace,
        sourceFallback: MappingNamespaceTree.Namespace,
        targetFallback: MappingNamespaceTree.Namespace,
        target: MappingNamespaceTree.Namespace,
        specConfig: JavaExecSpec.() -> Unit
    ) {
        val path = provider.minecraft.mappings.getRemapPath(
            source,
            sourceFallback,
            targetFallback,
            target
        )

        if (path.isEmpty()) {
            project.logger.info("[Unimined/SourceRemapper] detected empty remap path, jumping to after remap tasks")
            // copy input to output
            inputOutput.forEach { (input, output) ->
                if (input.exists()) {
                    output.parent.createDirectories()
                    input.copyTo(output, overwrite = true)
                }
            }
        }

        // create temp files for remap
        val inputs = inputOutput.keys.toList()
        var prevOutputs = inputs
        var prevNamespace = source
        var prevPrevNamespace = sourceFallback
        for (i in path.indices) {
            val step = path[i]
            project.logger.info("[Unimined/SourceRemapper]    $step")
            val targets = if (step == target) {
                inputs.map { inputOutput.getValue(it) }
            } else {
                inputs.map { tempDir.resolve("input-remapped-${it}-${step.name}") }
            }

            val mc = provider.minecraft.getMinecraft(
                prevNamespace,
                prevPrevNamespace
            )

            remapIntl(
                prevOutputs.zip(targets).toMap(),
                classpath,
                mc,
                prevNamespace,
                step,
                specConfig
            )

            prevOutputs = targets
            prevPrevNamespace = prevNamespace
            prevNamespace = step
        }
    }

    private fun remapIntl(
        inputOutput: Map<Path, Path>,
        classpath: FileCollection,
        minecraft: Path,
        source: MappingNamespaceTree.Namespace,
        target: MappingNamespaceTree.Namespace,
        specConfig: JavaExecSpec.() -> Unit
    ) {
        if (sourceRemapper.dependencies.isEmpty()) {
            remapper("1.0.5-SNAPSHOT")
        }

        val mappingFile = tempDir.createDirectories().resolve("${provider.minecraft.sourceSet.name}-${source.name}-${target.name}.srg")

        // export mappings
        val export = ExportMappingsTaskImpl.ExportImpl(provider.minecraft.mappings as MappingsProvider).apply {
            location = mappingFile.toFile()
            type = ExportMappingsTask.MappingExportTypes.SRG
            sourceNamespace = source
            targetNamespace = setOf(target)
        }
        export.validate()
        export.exportFunc((provider.minecraft.mappings as MappingsProvider).mappingTree)

        val roots = inputOutput.filter { it.key.exists() }.map { sourceDir ->
            sourceDir.key to inputOutput.getValue(sourceDir.key)
        }

        // run remap
        project.execOps.javaexec { spec ->
            spec.classpath(sourceRemapper)
            spec.mainClass.set("com.replaymod.gradle.remap.MainKt")
            spec.args = listOf(
                "-cp",
                (classpath.files.map { it.toPath() }
                    .filter { !provider.minecraft.isMinecraftJar(it) }
                    .filter { it.exists() } + listOf(minecraft))
                    .joinToString(File.pathSeparator) { it.absolutePathString() },
                *roots.flatMap { listOf("-r", it.first.absolutePathString(), it.second.absolutePathString()) }.toTypedArray(),
                "-m",
                mappingFile.toFile().absolutePath
            )
            specConfig(spec)
            project.logger.info("[Unimined/SourceRemapper]    ${spec.args!!.joinToString(" ")}")
        }.rethrowFailure().assertNormalExitValue()

    }

}