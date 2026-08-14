package xyz.wagyourtail.unimined.api.runs

import groovy.lang.Closure
import kotlinx.serialization.json.JsonBuilder
import org.gradle.api.JavaVersion
import org.gradle.api.Task
import org.gradle.api.file.FileCollection
import org.gradle.api.tasks.*
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import xyz.wagyourtail.unimined.util.JSONBuilder
import xyz.wagyourtail.unimined.util.XMLBuilder
import xyz.wagyourtail.unimined.util.removeALl
import xyz.wagyourtail.unimined.util.withSourceSet
import java.nio.charset.StandardCharsets
import javax.inject.Inject
import kotlin.io.path.relativeTo

abstract class RunConfig @Inject constructor(
    @get:Input
    val sourceSet: SourceSet,
    @get:Internal
    val preRunTask: TaskProvider<Task>
) : JavaExec() {

    @get:Suppress("ACCIDENTAL_OVERRIDE")
    var javaVersion: JavaVersion?
        get() = super.getJavaVersion()
        set(value) {
            val toolchains = project.extensions.getByType(JavaToolchainService::class.java)
            if (value == null) {
                javaLauncher.set(toolchains.launcherFor { })
            } else {
                javaLauncher.set(toolchains.launcherFor {
                    it.languageVersion.set(JavaLanguageVersion.of(value.majorVersion))
                })
            }
        }

    @get:Suppress("ACCIDENTAL_OVERRIDE")
    var classpath: FileCollection
        get() = super.getClasspath()
        set(value) {
            super.setClasspath(value.filter { it.extension != "pom" })
        }

    @get:Internal
    val properties: MutableMap<String, () -> String> = mutableMapOf()

    init {
        group = "unimined_runs"
        dependsOn(preRunTask)
    }

    fun setProperty(
        key: String,
        value: Closure<String>
    ) {
        properties[key] = { value.call() }
    }

    private fun <T> applyProperties(arg: T): T {
        return if (arg is String) {
            arg.replace(Regex("\\$\\{([^}]+)}")) {
                val key = it.groupValues[1]
                if (properties.containsKey(key)) {
                    properties.getValue(key).invoke()
                } else {
                    project.logger.warn("[Unimined/RunConfig ${path}]Property $key not found")
                    ""
                }
            } as T
        } else arg
    }

    private fun applyAll() {
        args = args!!.map { applyProperties(it) }
        jvmArgs = jvmArgs!!.map { applyProperties(it) }
        environment = environment.mapValues { (_, value) -> applyProperties(value) }
    }

    @TaskAction
    override fun exec() {
        applyAll()
        workingDir.mkdirs()
        super.exec()
    }

    fun createIdeaRunConfig() {
        if (!this.enabled) return
        applyAll()
        val file = project.rootDir.resolve(".idea")
            .resolve("runConfigurations")
            .resolve(
                "${if (project.path != ":") project.path.replace(":", "_") + "_" else ""}+${
                    name.withSourceSet(
                        sourceSet
                    )
                }.xml"
            )

        val configuration = XMLBuilder("configuration").addStringOption("default", "false")
            .addStringOption("name", buildString {
                if (project != project.rootProject) append(project.path)
                append(" ")
                if (description != null) {
                    append(description)
                    if (sourceSet.name != "main") append(" (${sourceSet.name})")
                } else {
                    append(name)
                }
            })
            .addStringOption("type", "Application")
            .addStringOption("factoryName", "Application")

        javaLauncher.orNull?.let { launcher ->
            configuration.append(
                XMLBuilder("option").addStringOption("name", "ALTERNATIVE_JRE_PATH")
                    .addStringOption("value", launcher.metadata.installationPath.asFile.absolutePath),
                XMLBuilder("option").addStringOption("name", "ALTERNATIVE_JRE_PATH_ENABLED")
                    .addStringOption("value", "true")
            )
        }

        configuration.append(
            XMLBuilder("envs").append(
                *(environment.removeALl(System.getenv())).map { (key, value) ->
                    XMLBuilder("env").addStringOption("name", key).addStringOption("value", value.toString())
                }.toTypedArray()
            ),
            XMLBuilder("option").addStringOption("name", "MAIN_CLASS_NAME")
                .addStringOption("value", mainClass.getOrElse("")),
            XMLBuilder("module").addStringOption(
                "name",
                "${
                    if (project != project.rootProject) "${project.rootProject.name}${
                        project.path.replace(
                            ":",
                            "."
                        )
                    }" else project.name
                }.${sourceSet.name}".replace(
                    " ",
                    "_"
                )
            ),
            XMLBuilder("classpathModifications").append(
                *classpath.filter { !sourceSet.runtimeClasspath.contains(it) }.map {
                    XMLBuilder("entry").addStringOption("path", it.absolutePath)
                }.toTypedArray(),
                *sourceSet.runtimeClasspath.filter { !classpath.contains(it) }.map {
                    XMLBuilder("entry").addStringOption("exclude", "true").addStringOption("path", it.absolutePath)
                }.toTypedArray()
            ),
            XMLBuilder("option").addStringOption("name", "PROGRAM_PARAMETERS")
                .addStringOption(
                    "value",
                    args?.joinToString(" ") { if (it.contains(" ")) "&quot;$it&quot;" else it } ?: ""),
            XMLBuilder("option").addStringOption("name", "VM_PARAMETERS")
                .addStringOption(
                    "value",
                    jvmArgs?.joinToString(" ") { if (it.contains(" ")) "&quot;$it&quot;" else it } ?: ""),
            XMLBuilder("option").addStringOption("name", "WORKING_DIRECTORY")
                .addStringOption(
                    "value",
                    "\$PROJECT_DIR\$/${
                        workingDir.toPath()
                            .relativeTo(project.rootProject.projectDir.toPath())
                    }"
                ),
        )

        val mv2 = XMLBuilder("method")
            .addStringOption("v", "2")
            .append(
                XMLBuilder("option").addStringOption("name", "Make").addStringOption("enabled", "true")
            )

        mv2.append(
            XMLBuilder("option")
                .addStringOption("name", "Gradle.BeforeRunTask")
                .addStringOption("enabled", "true")
                .addStringOption("tasks", preRunTask.name)
                .addStringOption(
                    "externalProjectPath",
                    "\$PROJECT_DIR\$/${
                        project.projectDir.toPath()
                            .relativeTo(project.rootProject.projectDir.toPath())
                    }"
                )
                .addStringOption("vmOptions", "")
                .addStringOption("scriptParameters", "")
        )

        configuration.append(mv2)

        file.parentFile.mkdirs()
        file.writeText(
            XMLBuilder("component").addStringOption("name", "ProjectRunConfigurationManager").append(
                configuration
            ).toString(),
            StandardCharsets.UTF_8
        )

    }

    fun createVSCodeRunConfig() {
        if (!this.enabled) return
        applyAll()

        val vscodeDir = project.rootDir.resolve(".vscode")
        val file = vscodeDir.resolve("launch.json")

        val configName = buildString {
            if (project != project.rootProject) append(project.path)
            append(" ")
            if (description != null) {
                append(description)
                if (sourceSet.name != "main") append(" (${sourceSet.name})")
            } else {
                append(name)
            }
        }

        val projectName = buildString {
            if (project != project.rootProject) {
                append(project.rootProject.name)
                append(project.path.replace(":", "."))
            } else {
                append(project.name)
            }
            append(".${sourceSet.name}")
        }.replace(" ", "_")

        val relativeWd = workingDir.toPath().relativeTo(project.rootProject.projectDir.toPath()).toString()
        val cwdValue = if (relativeWd.isEmpty()) "\${workspaceFolder}" else "\${workspaceFolder}/$relativeWd"

        val configurationBuilder = JSONBuilder("")
            .addStringOption("type", "java")
            .addStringOption("name", configName)
            .addStringOption("request", "launch")
            .addStringOption("mainClass", mainClass.getOrElse(""))
            .addStringOption("projectName", projectName)

        val argsBuilder = JSONBuilder("")
        args?.forEach { argsBuilder.addKeyOption(it) }
        configurationBuilder.append(argsBuilder)

        configurationBuilder.addStringOption("vmArgs", jvmArgs?.joinToString(" ") ?: "")

        val envBuilder = JSONBuilder("")
        val systemEnv = System.getenv()
        environment
            .filter { (k, v) -> systemEnv[k] != v?.toString() }
            .forEach { (k, v) -> envBuilder.addStringOption(k, v?.toString() ?: "") }
        configurationBuilder.append(envBuilder)

        configurationBuilder.addStringOption("cwd", cwdValue.replace("\\", "/"))

        javaLauncher.orNull?.let { launcher ->
            configurationBuilder.addStringOption("javaHome", launcher.metadata.installationPath.asFile.absolutePath)
        }

        configurationBuilder.addStringOption("preLaunchTask", preRunTask.name)

        vscodeDir.mkdirs()

        val finalJsonString = if (file.exists()) {
            val content = file.readText(java.nio.charset.StandardCharsets.UTF_8)

            if (content.contains("\"name\": \"$configName\"")) {
                content
            } else {
                val target = "\"configurations\": ["
                if (content.contains(target)) {
                    content.replace(target, "$target\n        ${configurationBuilder.toString()},")
                } else {
                    content
                }
            }
        } else {
            val rootBuilder = JSONBuilder("")
            rootBuilder.addStringOption("version", "0.2.0")

            val configsArray = JSONBuilder("")
            configsArray.append(configurationBuilder)
            rootBuilder.append(configsArray)

            rootBuilder.toString()
        }

        file.writeText(finalJsonString, java.nio.charset.StandardCharsets.UTF_8)
    }

}
