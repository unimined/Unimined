package xyz.wagyourtail.unimined.transform.forge.internal.mcpconfig

import org.gradle.api.GradleException
import org.gradle.api.JavaVersion
import org.gradle.api.logging.Logging
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.process.ExecOperations
import xyz.wagyourtail.unimined.transform.forge.internal.mcpconfig.McpConfigRunner
import java.io.File
import java.nio.file.Path
import javax.inject.Inject

/**
 * `ExecOperations`-backed [McpConfigRunner.ExecPort] — create via
 * `objects.newInstance(GradleExecPort::class.java)` so Gradle injects
 * the services, then set [useToolchains]/[defaultJavaVersion].
 *
 * Toolchain semantics follow 1.x `useToolchains` (opt-out): a tool's
 * declared `java_version` (or [defaultJavaVersion] — the MC version's
 * java requirement) is resolved through [JavaToolchainService]; with
 * toolchains disabled the build JVM must satisfy the requirement or
 * the exec errors.
 */
internal open class GradleExecPort @Inject constructor(
    private val execOps: ExecOperations,
    private val toolchains: JavaToolchainService
) : McpConfigRunner.ExecPort {

    private val logger = Logging.getLogger(GradleExecPort::class.java)

    /** Whether tool execs resolve Gradle java toolchains (1.x
     * `useToolchains` opt-out). */
    var useToolchains: () -> Boolean = { true }

    /** MC environment's java requirement — used when a tool omits
     * `java_version`. */
    var defaultJavaVersion: () -> Int? = { null }

    override fun exec(
        mainClass: String?, classpath: List<Path>,
        args: List<String>, jvmArgs: List<String>,
        javaVersion: Int?
    ) {
        val required = javaVersion ?: defaultJavaVersion()
        val executable = if (useToolchains()) {
            if (required != null) {
                try {
                    toolchains.launcherFor {
                        it.languageVersion.set(JavaLanguageVersion.of(required))
                    }.get().executablePath.asFile.absolutePath
                } catch (e: GradleException) {
                    throw IllegalStateException(
                        "Failed to find a java toolchain for version $required", e
                    )
                }
            } else {
                // no version requirement anywhere — build JVM
                File(System.getProperty("java.home"), "bin/java").absolutePath
            }
        } else {
            if (required != null &&
                JavaVersion.current() < JavaVersion.toVersion(required)
            ) {
                error(
                    "tool $mainClass requires java $required but toolchains " +
                            "are disabled and the build JVM is ${JavaVersion.current()}"
                )
            }
            File(System.getProperty("java.home"), "bin/java").absolutePath
        }
        val result = execOps.javaexec { spec ->
            spec.executable = executable
            spec.mainClass.set(mainClass ?: error("no main class"))
            spec.classpath(classpath.map { it.toFile() })
            spec.args(args)
            spec.jvmArgs(jvmArgs)
            spec.isIgnoreExitValue = true
        }
        if (result.exitValue != 0) {
            error("tool $mainClass exited ${result.exitValue}")
        }
    }
}
