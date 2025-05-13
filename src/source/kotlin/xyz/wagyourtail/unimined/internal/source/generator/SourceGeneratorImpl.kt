package xyz.wagyourtail.unimined.internal.source.generator

import org.apache.commons.compress.archivers.zip.ZipFile
import org.gradle.api.GradleException
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.artifacts.Dependency
import org.gradle.api.file.FileCollection
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.LineNumberNode
import xyz.wagyourtail.unimined.api.source.generator.SourceGenerator
import xyz.wagyourtail.unimined.internal.source.SourceProvider
import xyz.wagyourtail.unimined.util.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.*

class SourceGeneratorImpl(val project: Project, val provider: SourceProvider) : SourceGenerator {

    override var javaVersion: JavaVersion? = JavaVersion.VERSION_17

    override var jvmArgs: List<String> by FinalizeOnRead(listOf(
        "-Xmx4G",
        "-Xms1G",
        "-Xss4M",
    ))

    override var args: List<String> by FinalizeOnRead(listOf(
        "-jrt=1"
    ))


    val generator = project.configurations.maybeCreate("sourceGenerator".withSourceSet(provider.minecraft.sourceSet))

    override fun generator(dep: Any, action: Dependency.() -> Unit) {
        if (!generator.dependencies.isEmpty()) throw IllegalStateException("Generator already set")
        generator.dependencies.add(
            project.dependencies.create(
                if (dep is String && !dep.contains(":")) {
                    "org.vineflower:vineflower:$dep"
                } else {
                    dep
                }
            )
            .also {
                action(it)
            }
        )

    }

    override fun generate(classpath: FileCollection, inputPath: Path, outputPath: Path, linemappedPath: Path?) {
        if (generator.dependencies.isEmpty()) {
            generator("1.11.0")
        }

        outputPath.deleteIfExists()
        project.execOps.javaexec { spec ->

            val javaVersion = javaVersion
            if (javaVersion != null) {
                val toolchain = project.extensions.getByType(JavaToolchainService::class.java)
                spec.executable = toolchain.launcherFor {
                    it.languageVersion.set(JavaLanguageVersion.of(javaVersion.majorVersion))
                }.get().executablePath.asFile.absolutePath
            }

            spec.jvmArgs(jvmArgs)
            spec.classpath(generator)

            val args = args.toMutableList()
            if (linemappedPath != null) {
                args += "-bsm=1"
                args += "-dcl=1"
            }
            args += listOf(
                "-e=" + classpath.joinToString(File.pathSeparator) { it.absolutePath },
                inputPath.absolutePathString(),
                "--file",
                outputPath.absolutePathString()
            )
            spec.args = args
        }.rethrowFailure().assertNormalExitValue()

        if (linemappedPath != null) {
            linemappedPath.deleteIfExists()
            lineMapJar(inputPath, outputPath, linemappedPath)
        }
    }

    private fun readLineMappings(extra: ByteArray?): Map<Int, Int>? {
        if (extra == null || extra.size < (Short.SIZE_BYTES * 2)) return null
        val buffer = ByteBuffer.wrap(extra)
        buffer.order(ByteOrder.LITTLE_ENDIAN)

        // find (short) 0x4646 in buffer
        while (buffer.short != 0x4646.toShort()) {
            val length = buffer.short
            // forward by length bytes
            buffer.position(buffer.position() + length)
            if (!buffer.hasRemaining()) return null
        }

        val map = mutableMapOf<Int, Int>()
        val length = buffer.short
        if (length < 1) return null
        val version = buffer.get()
        if (version != 1.toByte()) {
            project.logger.warn("[SourceGenerator/LineMapping] unknown linemap version $version")
            return null
        }

        // calculate map size based on length
        val mapSize = (length - 1) / (Short.SIZE_BYTES * 2)
        for (i in 0 until mapSize) {
            if (!buffer.hasRemaining()) {
                project.logger.warn("[SourceGenerator/LineMapping] linemap ended early, expected $mapSize entries, got $i")
                return null
            }
            val key = buffer.short.toInt()
            val value = buffer.short.toInt()
            map[key] = value
        }

        // return null if empty
        if (map.isEmpty()) return null
        return map
    }

    private fun lineMapJar(binaryJar: Path, sourcesJar: Path, linemappedJar: Path) {
        val extras = mutableMapOf<String, ByteArray>()
        sourcesJar.forEntryInZip { entry, _ ->
            if (entry.extra != null) extras[entry.name] = entry.extra
            else extras[entry.name] = ByteArray(0)
        }

        linemappedJar.openZipFileSystem("create" to true, "mutable" to true).use { fs ->
            binaryJar.forEachInZip { s, inputStream ->
                val entry = fs.getPath(s)
                entry.parent?.createDirectories()

                if (entry.extension != "class") {
                    entry.outputStream().use(inputStream::copyTo)
                    return@forEachInZip
                }

                val classReader = ClassReader(inputStream)
                val classNode = ClassNode()
                classReader.accept(classNode, 0)

                val sourceFile = classNode.sourceFile ?:
                    (classNode.name.substringAfterLast("/").substringBefore("$") + ".java")
                val sourceEntry = entry.resolveSibling(sourceFile)

                val source = extras[sourceEntry.toString()]
                if (source != null) {
                    val lineMappings = readLineMappings(source)
                    if (lineMappings != null) {
                        remapLines(classNode, lineMappings)
                    }
                } else {
                    project.logger.warn("[SourceGenerator/LineMapping] could not find source for $entry tried $sourceEntry")
                }

                val classWriter = ClassWriter(classReader, 0)
                classNode.accept(classWriter)
                entry.outputStream().use { os ->
                    os.write(classWriter.toByteArray())
                }

            }
        }

    }

    private fun remapLines(classNode: ClassNode, lineMappings: Map<Int, Int>) {
        for (method in classNode.methods) {
            for (insn in method.instructions) {
                if (insn is LineNumberNode && insn.line in lineMappings) {
                    insn.line = lineMappings[insn.line]!!
                }
            }
        }
    }


}
