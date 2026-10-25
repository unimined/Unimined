package xyz.wagyourtail.unimined.remapper.source

import kotlin.reflect.KClass
import org.gradle.api.GradleException
import xyz.wagyourtail.unimined.api.mapping.MappingTree
import xyz.wagyourtail.unimined.api.spec.RemapSpec
import xyz.wagyourtail.unimined.remap.spi.ArtifactStep
import xyz.wagyourtail.unimined.remap.spi.RemapStepType
import xyz.wagyourtail.unimined.remap.spi.RemapSurface
import xyz.wagyourtail.unimined.remap.spi.StepContext
import java.io.File
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.outputStream

/**
 * `remap` step on the SOURCES surface, backed by the
 * `xyz.wagyourtail.unimined:source-remap` tool (ReplayMod fork) — the 1.x
 * `SourceRemapperImpl` path. The tool remaps source *trees*, so the step
 * unpacks the input jar, runs the tool in a JVM subprocess, and repacks.
 */
class SourceRemapStep : RemapStepType<SourceRemapStepSpec> {

    override val name = "source"
    override val stepName = "remap"
    override val specType: KClass<SourceRemapStepSpec> = SourceRemapStepSpec::class
    override val surfaces: Set<RemapSurface> = setOf(RemapSurface.SOURCES)
    override val needsMappingTree = true
    override val needsClasspath = true

    override fun supports(spec: RemapSpec) = true

    override fun create(spec: SourceRemapStepSpec, ctx: StepContext) = ArtifactStep { input, c ->
        val toolCp = spec.toolClasspath.files.map { it.toPath() }
        require(toolCp.isNotEmpty()) {
            "sourceRemap: toolClasspath is empty — wire a configuration " +
                "resolving 'xyz.wagyourtail.unimined:source-remap'"
        }
        val tree = c.mappings?.view(MappingTree::class.java)
            ?: throw GradleException("source remap requires mapping data (needsMappingTree)")

        val inDir = c.workDir.resolve("src-in").createDirectories()
        val outDir = c.workDir.resolve("src-out").createDirectories()
        unpack(input, inDir)

        val srgFile = c.workDir.resolve("mappings.srg")
        SrgWriter.write(tree, c.spec.sourceNamespace, c.spec.targetNamespace, srgFile)

        val classpath = (ctx.classpath + spec.extraClasspath.files.map { it.toPath() })
            .filter { it.toFile().exists() }
        val javaBin = File(System.getProperty("java.home"), "bin/java").absolutePath

        val args = buildList {
            add(javaBin)
            add("-cp")
            add(toolCp.joinToString(File.pathSeparator))
            add("com.replaymod.gradle.remap.MainKt")
            add("-cp")
            add(classpath.joinToString(File.pathSeparator))
            addAll(listOf("-r", inDir.toString(), outDir.toString()))
            addAll(listOf("-m", srgFile.toString()))
            addAll(spec.extraArgs.get())
        }
        c.logger.info("[Unimined/SourceRemap] ${args.joinToString(" ")}")

        val proc = ProcessBuilder(args).redirectErrorStream(true).start()
        val output = proc.inputStream.bufferedReader().readText()
        if (proc.waitFor() != 0) {
            throw GradleException("source-remap tool failed:\n$output")
        }
        c.logger.debug(output)

        val out = c.workDir.resolve("sources-remapped.jar")
        pack(outDir, out)
        out
    }

    private fun unpack(jar: Path, dir: Path) {
        JarFile(jar.toFile()).use { j ->
            for (entry in j.entries()) {
                if (entry.isDirectory) continue
                val target = dir.resolve(entry.name).normalize()
                target.parent.createDirectories()
                j.getInputStream(entry).use { it.copyTo(target.outputStream()) }
            }
        }
    }

    private fun pack(dir: Path, jar: Path) {
        JarOutputStream(jar.outputStream()).use { out ->
            dir.toFile().walkTopDown().filter { it.isFile }.forEach { f ->
                out.putNextEntry(JarEntry(dir.relativize(f.toPath()).toString().replace('\\', '/')))
                f.inputStream().copyTo(out)
                out.closeEntry()
            }
        }
    }

}
