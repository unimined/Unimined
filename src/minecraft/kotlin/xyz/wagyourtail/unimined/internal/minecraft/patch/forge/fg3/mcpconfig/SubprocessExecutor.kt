package xyz.wagyourtail.unimined.internal.minecraft.patch.forge.fg3.mcpconfig

import org.apache.commons.io.output.NullOutputStream
import org.gradle.api.Action
import org.gradle.api.Project
import org.gradle.api.logging.LogLevel
import org.gradle.api.logging.configuration.ShowStacktrace
import org.gradle.process.ExecResult
import org.gradle.process.JavaExecSpec
import xyz.wagyourtail.unimined.util.execOps

object SubprocessExecutor {
    fun shouldShowVerboseStdout(project: Project): Boolean {
        // if running with INFO or DEBUG logging
        return project.gradle.startParameter.logLevel < LogLevel.LIFECYCLE
    }

    fun shouldShowVerboseStderr(project: Project): Boolean {
        // if stdout is shown or stacktraces are visible so that errors printed to stderr show up
        return shouldShowVerboseStdout(project) || project.gradle.startParameter.showStacktrace != ShowStacktrace.INTERNAL_EXCEPTIONS
    }

    /**
     * Executes a [javaexec][Project.javaexec] action with suppressed output.
     *
     * @param project      the project
     * @param configurator the `javaexec` configuration action
     * @return the execution result
     */
    fun exec(project: Project, configurator: Action<in JavaExecSpec>): ExecResult {
        return project.execOps.javaexec { spec: JavaExecSpec ->
            spec.workingDir(project.rootProject.projectDir)
            configurator.execute(spec)
            if (shouldShowVerboseStdout(project)) {
                spec.standardOutput = System.out
            } else {
                spec.standardOutput = NullOutputStream.NULL_OUTPUT_STREAM
            }
            if (shouldShowVerboseStderr(project)) {
                spec.errorOutput = System.err
            } else {
                spec.errorOutput = NullOutputStream.NULL_OUTPUT_STREAM
            }
        }
    }
}