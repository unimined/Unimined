package xyz.wagyourtail.unimined.ide

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import xyz.wagyourtail.unimined.ide.eclipse.EclipseLaunchXml
import xyz.wagyourtail.unimined.ide.idea.IdeaRunXml
import xyz.wagyourtail.unimined.ide.vscode.VsCodeLaunchJson

/** Output-shape tests for the three IDE emitters. */
class IdeEmittersTest {

    private val model = IdeRunModel(
        name = "client",
        environmentId = "main",
        side = "CLIENT",
        prod = false,
        mainClass = "net.minecraft.client.Minecraft",
        jvmArgs = listOf("-Xmx2G", "-Ddebug=true"),
        programArgs = listOf("--username", "Dev"),
        environmentVariables = mapOf("MC_ENV" to "dev"),
        workingDirectory = "run/main-client",
        classpath = listOf(
            ClasspathEntry("/m2/minecraft-dev.jar", group = ClasspathGroup.MINECRAFT),
            ClasspathEntry("/m2/loader.jar", group = ClasspathGroup.LOADER),
            ClasspathEntry("/m2/mod-remapped.jar", group = ClasspathGroup.MODS),
        ),
    )

    @Test
    fun `idea config carries launch fields`() {
        val xml = IdeaRunXml.renderRunConfiguration(
            model, "unimined main:client", "bta.main"
        )
        assertTrue(xml.contains("type=\"Application\""))
        assertTrue(xml.contains("name=\"unimined main:client\""))
        assertTrue(xml.contains("module name=\"bta.main\""))
        assertTrue(xml.contains("MAIN_CLASS_NAME"))
        assertTrue(xml.contains("net.minecraft.client.Minecraft"))
        assertTrue(xml.contains("--username Dev"))
        assertTrue(xml.contains("-Xmx2G -Ddebug=true"))
        assertTrue(xml.contains("\$PROJECT_DIR\$/run/main-client"))
        assertTrue(xml.contains("env name=\"MC_ENV\" value=\"dev\""))
        // classpath modifications carry the resolved jars
        assertTrue(xml.contains("classpathModifications"))
        assertTrue(xml.contains("/m2/minecraft-dev.jar"))
        assertTrue(xml.contains("/m2/mod-remapped.jar"))
    }

    @Test
    fun `idea config emits Make before-run`() {
        val xml = IdeaRunXml.renderRunConfiguration(
            model, "x", "y.main"
        )
        assertTrue(xml.contains("Make"))
    }

    @Test
    fun `vscode launch json is well formed`() {
        val json = VsCodeLaunchJson.renderLaunchJson(
            listOf(model), "bta"
        )
        assertTrue(json.contains("\"version\": \"0.2.0\""))
        assertTrue(json.contains("\"type\": \"java\""))
        assertTrue(json.contains("\"request\": \"launch\""))
        assertTrue(json.contains("unimined main:client"))
        assertTrue(json.contains("net.minecraft.client.Minecraft"))
        assertTrue(json.contains("\"projectName\": \"bta\""))
        assertTrue(json.contains("\${workspaceFolder}/run/main-client"))
        assertTrue(json.contains("\"classPaths\": ["))
        assertTrue(json.contains("\"/m2/minecraft-dev.jar\""))
        assertTrue(json.contains("\"MC_ENV\": \"dev\""))
        // well-formed: balanced braces, no trailing comma
        assertTrue(json.trimEnd().endsWith("}"))
        assertFalse(json.contains(",\n        ]"))
        assertFalse(json.contains(",\n    ]"))
    }

    @Test
    fun `eclipse launch file carries explicit classpath`() {
        val xml = EclipseLaunchXml.renderLaunchFile(
            model, "bta", "unimined main:client"
        )
        assertTrue(
            xml.contains(
                "type=\"org.eclipse.jdt.launching.localJavaApplication\""
            )
        )
        assertTrue(xml.contains("MAIN_TYPE"))
        assertTrue(xml.contains("net.minecraft.client.Minecraft"))
        assertTrue(xml.contains("PROJECT_ATTR"))
        assertTrue(xml.contains("DEFAULT_CLASSPATH\" value=\"false\""))
        // resolved jars land as embedded mementos in CLASSPATH
        assertTrue(xml.contains("runtimeClasspathEntry"))
        assertTrue(xml.contains("minecraft-dev.jar"))
        assertTrue(xml.contains("workspace_loc"))
    }
}
