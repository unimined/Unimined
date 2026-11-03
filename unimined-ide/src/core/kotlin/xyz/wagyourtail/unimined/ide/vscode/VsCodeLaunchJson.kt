package xyz.wagyourtail.unimined.ide.vscode

import xyz.wagyourtail.unimined.ide.IdeRunModel

/**
 * Pure renderer for VSCode `launch.json` — the Java debug
 * (`java`) configuration format consumed by the redhat Java
 * extension. `classPaths` carries explicit jar lists so both dev
 * and prod runs launch correctly without any buildship/JDT
 * classpath wiring.
 */
object VsCodeLaunchJson {

    private fun esc(s: String): String = s
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\t", "\\t")

    /**
     * A complete `launch.json` document from [models]. [projectName]
     * maps to the Java extension's `projectName` (the JDT project —
     * the Gradle project name).
     */
    fun renderLaunchJson(
        models: List<IdeRunModel>,
        projectName: String,
        envToDisplay: (IdeRunModel) -> String = {
            "unimined ${it.environmentId}:${it.name}"
        }
    ): String = buildString {
        append("{\n")
        append("    \"version\": \"0.2.0\",\n")
        append("    \"configurations\": [\n")
        models.forEachIndexed { i, model ->
            append("        {\n")
            append("            \"type\": \"java\",\n")
            append("            \"request\": \"launch\",\n")
            append(
                "            \"name\": " +
                        "\"${esc(envToDisplay(model))}\",\n"
            )
            append(
                "            \"mainClass\": " +
                        "\"${esc(model.mainClass)}\",\n"
            )
            append(
                "            \"projectName\": " +
                        "\"${esc(projectName)}\",\n"
            )
            if (model.programArgs.isNotEmpty()) {
                append(
                    "            \"args\": " +
                            "\"${esc(model.programArgs.joinToString(" "))}\",\n"
                )
            }
            if (model.jvmArgs.isNotEmpty()) {
                append(
                    "            \"vmArgs\": " +
                            "\"${esc(model.jvmArgs.joinToString(" "))}\",\n"
                )
            }
            append(
                "            \"cwd\": " +
                        "\"\${workspaceFolder}/${esc(model.workingDirectory)}\",\n"
            )
            if (model.environmentVariables.isNotEmpty()) {
                append("            \"env\": {\n")
                model.environmentVariables.entries
                    .forEachIndexed { j, (k, v) ->
                        append(
                            "                \"${esc(k)}\": " +
                                    "\"${esc(v)}\"" +
                                    (if (j < model.environmentVariables.size - 1) ",\n"
                                    else "\n")
                        )
                    }
                append("            },\n")
            }
            append("            \"classPaths\": [\n")
            model.classpath.forEachIndexed { j, entry ->
                append(
                    "                \"${esc(entry.jar)}\"" +
                            (if (j < model.classpath.size - 1) ",\n" else "\n")
                )
            }
            append("            ]\n")
            append("        }" + (if (i < models.size - 1) ",\n" else "\n"))
        }
        append("    ]\n")
        append("}\n")
    }
}
