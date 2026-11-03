package xyz.wagyourtail.unimined.ide.internal

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
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

    private fun displayName(model: IdeRunModel) =
        "unimined ${model.environmentId}:${model.name}"

    private fun fileName(model: IdeRunModel) =
        "unimined_${model.environmentId}_${model.name}"

    @TaskAction
    fun generate() {
        val out = outputDir.get().asFile
        out.mkdirs()
        val models = runModels.get()
        when (format.get()) {
            IdeFormat.IDEA -> models.forEach { model ->
                File(out, "${fileName(model)}.xml").writeText(
                    IdeaRunXml.renderRunConfiguration(
                        model, displayName(model), moduleName.get()
                    )
                )
            }
            IdeFormat.VSCODE -> File(out, "launch.json").writeText(
                VsCodeLaunchJson.renderLaunchJson(
                    models, projectName.get()
                )
            )
            IdeFormat.ECLIPSE -> models.forEach { model ->
                File(out, "${fileName(model)}.launch").writeText(
                    EclipseLaunchXml.renderLaunchFile(
                        model, projectName.get(), displayName(model)
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
