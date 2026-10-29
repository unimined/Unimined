package xyz.wagyourtail.unimined.remapper.source

import org.gradle.api.GradleException
import xyz.wagyourtail.unimined.api.spec.RemapSpec
import xyz.wagyourtail.unimined.mapping.MappingTree
import xyz.wagyourtail.unimined.mapping.element.mapping.MutableMethodMapping
import xyz.wagyourtail.unimined.mapping.formats.srg.SrgWriter
import xyz.wagyourtail.unimined.mapping.jvms.four.three.three.MethodDescriptor
import xyz.wagyourtail.unimined.mapping.jvms.four.two.one.InternalName
import xyz.wagyourtail.unimined.mapping.sink.emit
import xyz.wagyourtail.unimined.remap.spi.ArtifactStep
import xyz.wagyourtail.unimined.remap.spi.RemapStepType
import xyz.wagyourtail.unimined.remap.spi.RemapSurface
import xyz.wagyourtail.unimined.remap.spi.StepContext
import java.io.File
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import kotlin.io.path.bufferedWriter
import kotlin.io.path.createDirectories
import kotlin.io.path.outputStream
import kotlin.reflect.KClass

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
        writeSrg(tree, c.spec.sourceNamespace, c.spec.targetNamespace, srgFile)

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
        c.log.subLogger("source-remap").info(args.joinToString(" "))

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

    /**
     * Emit standard SRG (`PK:`/`CL:`/`FD:`/`MD:`) through UML's
     * [SrgWriter]. UML drops method rows whose dst entry lacks a
     * descriptor, so name-only dst cells are first filled with the
     * source descriptor remapped by SRG rules — `CL:` row first, then
     * the longest `PK:` prefix (package rows move classes even without
     * a class row, which is stricter than [MappingTree.map]'s
     * unmapped-stays fallback).
     */
    internal fun writeSrg(tree: MappingTree, source: String, target: String, out: Path) {
        val src = tree.namespaces.firstOrNull { it.name == source }
            ?: throw GradleException(
                "source remap: namespace '$source' not in the mapping tree"
            )
        val dst = tree.namespaces.firstOrNull { it.name == target }
            ?: throw GradleException(
                "source remap: namespace '$target' not in the mapping tree"
            )

        fun mapClass(internal: String): String {
            tree.getClass(src, InternalName.unchecked(internal))
                ?.names?.get(dst)?.let { return it.value }
            val (pkg, name) = InternalName.unchecked(internal).getParts()
            val best = tree.packages
                .filter { pkg.value.startsWith(it.names[src]?.value ?: return@filter false) }
                .maxByOrNull { it.names[src]!!.value.length }
            return if (best != null) {
                (best.names[dst]?.value ?: pkg.value) +
                        pkg.value.removePrefix(best.names[src]!!.value) + name.value
            } else internal
        }

        fun mapDesc(desc: String): String =
            Regex("L([^;]+);").replace(desc) { "L${mapClass(it.groupValues[1])};" }
        for (cls in tree.classes) {
            for (m in cls.methods) {
                if (m !is MutableMethodMapping) continue
                if (m.entries[dst]?.descriptor != null) continue
                m.descs[src]?.let {
                    m.setDescriptors(
                        mapOf(dst to MethodDescriptor.unchecked(mapDesc(it.value)))
                    )
                }
            }
        }
        out.bufferedWriter().use { w ->
            tree.emit(SrgWriter.write({ w.append(it) }), nsFilter = listOf(src, dst))
        }
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
