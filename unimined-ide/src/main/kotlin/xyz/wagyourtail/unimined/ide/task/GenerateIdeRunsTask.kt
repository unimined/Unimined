package xyz.wagyourtail.unimined.ide.task

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import xyz.wagyourtail.unimined.ide.IdeRunModel
import xyz.wagyourtail.unimined.ide.eclipse.EclipseLaunchXml
import xyz.wagyourtail.unimined.ide.idea.IdeaRunXml
import xyz.wagyourtail.unimined.ide.vscode.VsCodeLaunchJson
import java.io.File

/**
 * Emits IDE launch files for a set of resolved [IdeRunModel]s —
 * one format per task instance. Models are resolved lazily at
 * execution (the provider chain carries the env launch metadata +
 * run mutators), so nothing touches env providers during
 * configuration.
 */
@DisableCachingByDefault(because = "models resolve live" +
        " configurations at execution — nothing cacheable to hash")
abstract class GenerateIdeRunsTask : DefaultTask() {

    /** Which IDE format to emit. */
    enum class IdeFormat { IDEA, VSCODE, ECLIPSE }

    @get:Internal
    abstract val runModels: ListProperty<IdeRunModel>

    @get:Input
    abstract val format: Property<IdeFormat>

    /** Gradle project name — Eclipse `PROJECT_ATTR` / VSCode
     *  `projectName`. */
    @get:Input
    abstract val projectName: Property<String>

    /** IntelliJ module name (`<project>.<sourceSet>`) — IDEA only. */
    @get:Input
    abstract val moduleName: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    /** Per-run `@argfile`s — jvm args + resolved `-cp`, written here
     *  so the IDE config files stay byte-stable across dependency
     *  changes (the config points at the file; only its content
     *  refreshes). */
    @get:OutputDirectory
    abstract val argfilesDir: DirectoryProperty

    private fun displayName(model: IdeRunModel) =
        "unimined ${model.environmentId}:${model.name}"

    private fun fileName(model: IdeRunModel) =
        "unimined_${model.environmentId}_${model.name}"

    private fun argfileFor(model: IdeRunModel): File =
        argfilesDir.get().asFile.resolve("${fileName(model)}.args")

    /** JVM `@argfile` content: one token per line — `-cp` + jvm args.
     *  Backslashes and quotes are escaped per the launcher format;
     *  program args + main class stay in the IDE config (IDE fields
     *  require them and their content is dep-stable). */
    private fun writeArgfile(model: IdeRunModel): File {
        fun tok(s: String): String {
            val esc = s.replace("\\", "\\\\").replace("\"", "\\\"")
            return if (esc.any { it.isWhitespace() }) "\"$esc\"" else esc
        }
        val args = argfileFor(model)
        args.parentFile.mkdirs()
        args.writeText(
            (model.jvmArgs + listOf(
                "-cp", model.classpath.joinToString(
                    java.io.File.pathSeparator) { it.jar }
            )).joinToString("\n") { tok(it) } + "\n"
        )
        return args
    }

    @TaskAction
    fun generate() {
        val out = outputDir.get().asFile
        out.mkdirs()
        val models = runModels.get()
        val argfiles = models.associate { it.name to writeArgfile(it) }
        when (format.get()) {
            IdeFormat.IDEA -> models.forEach { model ->
                File(out, "${fileName(model)}.xml").writeText(
                    IdeaRunXml.renderRunConfiguration(
                        model, displayName(model), moduleName.get(),
                        argfiles[model.name]?.absolutePath
                    )
                )
            }
            IdeFormat.VSCODE -> File(out, "launch.json").writeText(
                VsCodeLaunchJson.renderLaunchJson(
                    models, projectName.get()
                ) { argfiles[it.name]?.absolutePath }
            )
            IdeFormat.ECLIPSE -> models.forEach { model ->
                File(out, "${fileName(model)}.launch").writeText(
                    EclipseLaunchXml.renderLaunchFile(
                        model, projectName.get(), displayName(model),
                        argfiles[model.name]?.absolutePath
                    )
                )
            }
        }
        logger.lifecycle(
            "[unimined:ide] generated ${models.size} " +
                    "${format.get()} run config(s) in $out"
        )
    }
}
