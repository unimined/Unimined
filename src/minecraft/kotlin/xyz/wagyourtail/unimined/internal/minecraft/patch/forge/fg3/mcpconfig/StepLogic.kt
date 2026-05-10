package xyz.wagyourtail.unimined.internal.minecraft.patch.forge.fg3.mcpconfig

import org.gradle.api.Action
import org.gradle.api.logging.Logger
import org.gradle.process.JavaExecSpec
import xyz.wagyourtail.unimined.util.openZipFileSystem
import java.io.File
import java.io.IOException
import java.io.PrintWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.jar.Attributes
import java.util.jar.JarFile
import java.util.stream.Collectors
import kotlin.io.path.copyTo

interface StepLogic {

    @Throws(IOException::class)
    fun execute(context: ExecutionContext)

    fun getDisplayName(stepName: String): String {
        return stepName
    }

    interface ExecutionContext {
        fun logger(): Logger

        @Throws(IOException::class)
        fun setOutput(fileName: String): Path
        fun setOutput(output: Path): Path

        /** Mappings extracted from `data.mappings` in the MCPConfig JSON.  */
        fun mappings(): Path
        fun resolve(value: ConfigValue): String

        @Throws(IOException::class)
        fun download(url: String): Path
        fun javaexec(configurator: Action<in JavaExecSpec>)
        fun resolveCoordinates(coords: List<String>): List<Path>
        val minecraftLibraries: Set<File>

        fun resolve(configValues: List<ConfigValue>): List<String> {
            return configValues.map(::resolve)
        }
    }

    class OfFunction(private val function: McpConfigFunction): StepLogic {
        @Throws(IOException::class)
        override fun execute(context: ExecutionContext) {
            context.setOutput("output")
            if (function.classpath != null) {
                // spec 6+: resolve via Gradle, use explicit mainClass if provided
                val jars = context.resolveCoordinates(function.classpath)
                val mainClass = function.mainClass ?: JarFile(jars.first().toFile()).use { jf ->
                    jf.manifest.mainAttributes.getValue(Attributes.Name.MAIN_CLASS)
                        ?: error("No Main-Class in manifest of ${jars.first()}")
                }
                context.javaexec(Action { spec: JavaExecSpec ->
                    spec.classpath(jars.map { it.toFile() })
                    spec.mainClass.set(mainClass)
                    spec.args(context.resolve(function.args))
                    spec.jvmArgs(context.resolve(function.jvmArgs))
                })
            } else {
                val jar: Path = context.download(function.getDownloadUrl())
                var mainClass: String
                try {
                    JarFile(jar.toFile()).use { jarFile ->
                        mainClass = jarFile.manifest
                            .mainAttributes
                            .getValue(Attributes.Name.MAIN_CLASS)
                    }
                } catch (e: IOException) {
                    throw IOException("Could not determine main class for " + jar.toAbsolutePath(), e)
                }
                context.javaexec(Action { spec: JavaExecSpec ->
                    spec.classpath(jar)
                    spec.mainClass.set(mainClass)
                    spec.args(context.resolve(function.args))
                    spec.jvmArgs(context.resolve(function.jvmArgs))
                })
            }
        }

        override fun getDisplayName(stepName: String): String {
            return stepName + " with " + (function.version ?: function.classpath?.firstOrNull() ?: "unknown")
        }
    }

    class Strip: StepLogic {
        companion object {
            private fun trimLeadingSlash(string: String): String {
                if (string.startsWith(File.separator)) {
                    return string.substring(File.separator.length)
                } else if (string.startsWith("/")) {
                    return string.substring(1)
                }
                return string
            }
        }

        @Throws(IOException::class)
        override fun execute(context: ExecutionContext) {
            val filter = Files.readAllLines(context.mappings(), StandardCharsets.UTF_8).stream()
                .filter { s: String -> !s.startsWith("\t") }
                .map { s: String ->
                    s.split(" ".toRegex()).dropLastWhile { it.isEmpty() }
                        .toTypedArray()[0] + ".class"
                }
                .collect(Collectors.toSet())
            val input: Path = Paths.get(context.resolve(ConfigValue.Variable("input")))
            context.setOutput("stripped.jar").openZipFileSystem(mapOf("create" to true)).use { output ->
                input.openZipFileSystem().use { fs ->
                    for (path in Files.walk(fs.getPath("/"))) {
                        val trimLeadingSlash: String = trimLeadingSlash(path.toString())
                        if (!trimLeadingSlash.endsWith(".class")) continue
                        var has = filter.contains(trimLeadingSlash)
                        var s = trimLeadingSlash
                        while (s.contains("$") && !has) {
                            s = s.substring(0, s.lastIndexOf("$")) + ".class"
                            has = filter.contains(s)
                        }
                        if (!has) continue
                        val to: Path = output.getPath(trimLeadingSlash)
                        val parent = to.parent
                        if (parent != null) Files.createDirectories(parent)
                        path.copyTo(to, StandardCopyOption.COPY_ATTRIBUTES, StandardCopyOption.REPLACE_EXISTING)
                    }
                }
            }
        }
    }

    class ListLibraries: StepLogic {
        @Throws(IOException::class)
        override fun execute(context: ExecutionContext) {
            PrintWriter(Files.newBufferedWriter(context.setOutput("libraries.txt"))).use { writer ->
                for (lib in context.minecraftLibraries) {
                    writer.println("-e=" + lib.absolutePath)
                }
            }
        }
    }

    class DownloadManifestFile(private val download: Path): StepLogic {

        @Throws(IOException::class)
        override fun execute(context: ExecutionContext) {
            Files.copy(
                download,
                context.setOutput("output"),
                StandardCopyOption.REPLACE_EXISTING
            )
        }
    }

    class NoOp: StepLogic {
        @Throws(IOException::class)
        override fun execute(context: ExecutionContext) {
        }
    }

    class NoOpWithFile(private val path: (ExecutionContext) -> Path): StepLogic {
        @Throws(IOException::class)
        override fun execute(context: ExecutionContext) {
            context.setOutput(path(context))
        }
    }
}