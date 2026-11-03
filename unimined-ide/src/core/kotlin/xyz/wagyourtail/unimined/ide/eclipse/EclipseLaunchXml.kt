package xyz.wagyourtail.unimined.ide.eclipse

import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XML
import nl.adaptivity.xmlutil.serialization.XmlSerialName
import xyz.wagyourtail.unimined.ide.IdeRunModel

/**
 * Eclipse JDT `.launch` file — `org.eclipse.jdt.launching
 * .localJavaApplication`. Classpath entries are
 * `runtimeClasspathEntry` mementos embedded as attribute values
 * (the serializer escapes them); `DEFAULT_CLASSPATH=false` switches
 * the launch to our explicit list, so dev and prod classpaths are
 * both honored without any JDT/.classpath wiring.
 */
object EclipseLaunchXml {

    @Serializable
    @XmlSerialName("launchConfiguration")
    internal data class LaunchConfiguration(
        @XmlSerialName("type")
        val type: String,
        @XmlSerialName("stringAttribute")
        val stringAttributes: List<StringAttribute> = emptyList(),
        @XmlSerialName("booleanAttribute")
        val booleanAttributes: List<BooleanAttribute> = emptyList(),
        @XmlSerialName("listAttribute")
        val listAttributes: List<ListAttribute> = emptyList(),
        @XmlSerialName("mapAttribute")
        val mapAttributes: List<MapAttribute> = emptyList(),
    )

    @Serializable
    @XmlSerialName("stringAttribute")
    internal data class StringAttribute(
        @XmlSerialName("key")
        val key: String,
        @XmlSerialName("value")
        val value: String,
    )

    @Serializable
    @XmlSerialName("booleanAttribute")
    internal data class BooleanAttribute(
        @XmlSerialName("key")
        val key: String,
        @XmlSerialName("value")
        val value: Boolean,
    )

    @Serializable
    @XmlSerialName("listAttribute")
    internal data class ListAttribute(
        @XmlSerialName("key")
        val key: String,
        @XmlSerialName("listEntry")
        val entries: List<ListEntry>,
    )

    @Serializable
    @XmlSerialName("listEntry")
    internal data class ListEntry(
        @XmlSerialName("value")
        val value: String,
    )

    @Serializable
    @XmlSerialName("mapAttribute")
    internal data class MapAttribute(
        @XmlSerialName("key")
        val key: String,
        @XmlSerialName("mapEntry")
        val entries: List<MapEntry>,
    )

    @Serializable
    @XmlSerialName("mapEntry")
    internal data class MapEntry(
        @XmlSerialName("key")
        val key: String,
        @XmlSerialName("value")
        val value: String,
    )

    private val xml = XML { indent = 4 }

    /** The `<runtimeClasspathEntry>` memento for one archive —
     *  embedded raw inside a `CLASSPATH` listEntry value (JDT
     *  parses the embedded XML). */
    private fun cpMemento(jarPath: String): String =
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"no\"?>" +
                "<runtimeClasspathEntry internalArchive=\"$jarPath\" " +
                "path=\"3\" type=\"2\"/>"

    /**
     * One `.launch` file body for [model]. [projectName] is the
     * Eclipse project name (the Gradle project name under Buildship
     * import).
     */
    fun renderLaunchFile(
        model: IdeRunModel,
        projectName: String,
        displayName: String
    ): String {
        val stringAttrs = mutableListOf(
            StringAttribute(
                "org.eclipse.jdt.launching.MAIN_TYPE", model.mainClass
            ),
            StringAttribute(
                "org.eclipse.jdt.launching.PROJECT_ATTR", projectName
            ),
            StringAttribute("name", displayName),
            StringAttribute(
                "org.eclipse.jdt.launching.WORKING_DIRECTORY",
                "\${workspace_loc}/${model.workingDirectory}"
            ),
        )
        if (model.programArgs.isNotEmpty()) {
            stringAttrs += StringAttribute(
                "org.eclipse.jdt.launching.PROGRAM_ARGUMENTS",
                model.programArgs.joinToString(" ")
            )
        }
        if (model.jvmArgs.isNotEmpty()) {
            stringAttrs += StringAttribute(
                "org.eclipse.jdt.launching.VM_ARGUMENTS",
                model.jvmArgs.joinToString(" ")
            )
        }
        val config = LaunchConfiguration(
            type = "org.eclipse.jdt.launching.localJavaApplication",
            stringAttributes = stringAttrs,
            booleanAttributes = listOf(
                BooleanAttribute(
                    "org.eclipse.jdt.launching.DEFAULT_CLASSPATH", false
                )
            ),
            listAttributes = buildList {
                if (model.environmentVariables.isNotEmpty()) {
                    add(
                        ListAttribute(
                            "org.eclipse.debug.core.environmentVariables",
                            model.environmentVariables.map { (k, v) ->
                                ListEntry("$k=$v")
                            }
                        )
                    )
                }
                add(
                    ListAttribute(
                        "org.eclipse.jdt.launching.CLASSPATH",
                        model.classpath.map { ListEntry(cpMemento(it.jar)) }
                    )
                )
            },
        )
        return xml.encodeToString(
            LaunchConfiguration.serializer(), config
        )
    }
}
