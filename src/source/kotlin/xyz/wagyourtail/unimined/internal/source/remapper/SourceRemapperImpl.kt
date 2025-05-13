package xyz.wagyourtail.unimined.internal.source.remapper

import kotlinx.coroutines.runBlocking
import org.gradle.api.Project
import org.gradle.api.artifacts.ExternalModuleDependency
import org.gradle.api.file.FileCollection
import org.gradle.process.JavaExecSpec
import xyz.wagyourtail.unimined.api.source.remapper.SourceRemapper
import xyz.wagyourtail.unimined.api.unimined
import xyz.wagyourtail.unimined.internal.mapping.MappingsProvider
import xyz.wagyourtail.unimined.internal.mapping.task.ExportMappingsTaskImpl
import xyz.wagyourtail.unimined.internal.source.SourceProvider
import xyz.wagyourtail.unimined.mapping.Namespace
import xyz.wagyourtail.unimined.mapping.formats.srg.SrgWriter
import xyz.wagyourtail.unimined.util.execOps
import xyz.wagyourtail.unimined.util.withSourceSet
import java.io.File
import java.nio.file.Path
import kotlin.io.path.absolutePathString
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
        source: Namespace,
        target: Namespace,
        specConfig: JavaExecSpec.() -> Unit
    ) {

        val mc = provider.minecraft.getMinecraft(
            source
        )

        remapIntl(
            inputOutput,
            classpath,
            mc,
            source,
            target,
            specConfig
        )
    }

    private fun remapIntl(inputOutput: Map<Path, Path>, classpath: FileCollection, minecraft: Path, source: Namespace, target: Namespace, specConfig: JavaExecSpec.() -> Unit) = runBlocking {
        if (sourceRemapper.dependencies.isEmpty()) {
            remapper("1.0.5-SNAPSHOT")
        }

        val mappingFile = tempDir.createDirectories().resolve("${provider.minecraft.sourceSet.name}-${source.name}-${target.name}.srg")

        // export mappings
        val export = ExportMappingsTaskImpl.ExportImpl(provider.minecraft.mappings as MappingsProvider).apply {
            location = mappingFile.toFile()
            type = SrgWriter
            sourceNamespace = source
            targetNamespace = setOf(target)
        }
        export.validate()
        export.exportFunc((provider.minecraft.mappings as MappingsProvider).resolve())

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