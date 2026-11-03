package xyz.wagyourtail.unimined.ide.idea

import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XML
import nl.adaptivity.xmlutil.serialization.XmlSerialName
import xyz.wagyourtail.unimined.ide.IdeRunModel

/**
 * IntelliJ `Application` run configuration — serializes to
 * `.idea/runConfigurations/` files. The classpath is emitted via
 * `classpathModifications` so the launch works even when the
 * module's scope wiring hasn't picked up unimined deps; the
 * `<module>` reference supplies the JDK/project context.
 */
object IdeaRunXml {

    @Serializable
    @XmlSerialName("component")
    internal data class Component(
        @XmlSerialName("name")
        val name: String,
        val configuration: Configuration
    )

    @Serializable
    @XmlSerialName("configuration")
    internal data class Configuration(
        @XmlSerialName("default")
        val default: String,
        @XmlSerialName("name")
        val name: String,
        @XmlSerialName("type")
        val type: String,
        @XmlSerialName("factoryName")
        val factoryName: String,
        @XmlSerialName("option")
        val options: List<Option> = emptyList(),
        @XmlSerialName("module")
        val module: Module? = null,
        @XmlSerialName("envs")
        val envs: Envs? = null,
        @XmlSerialName("classpathModifications")
        val classpathModifications: ClasspathModifications? = null,
        @XmlSerialName("method")
        val method: Method? = null,
    )

    @Serializable
    @XmlSerialName("option")
    internal data class Option(
        @XmlSerialName("name")
        val name: String,
        @XmlSerialName("value")
        val value: String,
    )

    @Serializable
    @XmlSerialName("module")
    internal data class Module(
        @XmlSerialName("name")
        val name: String
    )

    @Serializable
    @XmlSerialName("envs")
    internal data class Envs(
        @XmlSerialName("env")
        val env: List<Env>
    )

    @Serializable
    @XmlSerialName("env")
    internal data class Env(
        @XmlSerialName("name")
        val name: String,
        @XmlSerialName("value")
        val value: String,
    )

    @Serializable
    @XmlSerialName("classpathModifications")
    internal data class ClasspathModifications(
        @XmlSerialName("entry")
        val entries: List<Entry>
    )

    @Serializable
    @XmlSerialName("entry")
    internal data class Entry(
        @XmlSerialName("path")
        val path: String
    )

    @Serializable
    @XmlSerialName("method")
    internal data class Method(
        @XmlSerialName("v")
        val v: String,
        @XmlSerialName("option")
        val options: List<Option> = emptyList(),
    )

    private val xml = XML { indent = 4 }

    /**
     * One `<configuration>` document for [model].
     * @param displayName the run's name in the IDE (`unimined <env>:<run>`)
     * @param moduleName the IntelliJ Gradle-imported module
     *  (`<projectName>.<sourceSetName>`)
     */
    fun renderRunConfiguration(
        model: IdeRunModel,
        displayName: String,
        moduleName: String
    ): String {
        val options = mutableListOf(
            Option("MAIN_CLASS_NAME", model.mainClass)
        )
        if (model.programArgs.isNotEmpty()) {
            options += Option(
                "PROGRAM_PARAMETERS", model.programArgs.joinToString(" ")
            )
        }
        if (model.jvmArgs.isNotEmpty()) {
            options += Option(
                "VM_PARAMETERS", model.jvmArgs.joinToString(" ")
            )
        }
        options += Option(
            "WORKING_DIRECTORY", "\$PROJECT_DIR\$/${model.workingDirectory}"
        )

        // explicit classpath — dev runs still work without the
        // module classpath-groups wiring; prod runs carry their own
        // (raw MC + shipped jars) classpath this way
        val config = Configuration(
            default = "false",
            name = displayName,
            type = "Application",
            factoryName = "Application",
            options = options,
            module = Module(moduleName),
            envs = if (model.environmentVariables.isEmpty()) null
            else Envs(model.environmentVariables.map { (k, v) ->
                Env(k, v)
            }),
            classpathModifications = ClasspathModifications(
                model.classpath.map { Entry(it.jar) }
            ),
            method = Method("2", listOf(Option("Make", "true"))),
        )
        return xml.encodeToString(
            Component.serializer(),
            Component(
                name = "ProjectRunConfigurationManager",
                configuration = config
            )
        )
    }
}
