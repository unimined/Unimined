package xyz.wagyourtail.unimined.mclauncher

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

/** Launch plan computation: rules, variables, legacy args, natives coords. */
class LaunchResolverTest {

    private fun data(json: String): VersionData =
        VersionData.parse(Json.parseToJsonElement(json) as JsonObject)

    private fun env(
        classpath: List<Path> = listOf(Path.of("/mc/client.jar")),
        session: Session = OfflineAuth("Dev").session(),
        features: Set<String> = emptySet()
    ) = LaunchEnvironment(
        classpath = classpath,
        gameDir = Path.of("/game"),
        assetsDir = Path.of("/assets"),
        assetIndexId = "1.20.1",
        nativesDir = Path.of("/natives"),
        session = session,
        features = features
    )

    @Test
    fun `modern arguments evaluate rules and substitute variables`() {
        val document = data("""
            {"id":"1.20.1","mainClass":"mc.Main",
             "arguments":{
               "game":[
                 "--username","${'$'}{auth_player_name}",
                 {"rules":[{"action":"allow","features":{"is_demo_user":true}}],
                  "value":"--demo"},
                 {"rules":[{"action":"allow"},
                          {"action":"disallow","os":{"name":"windows"}}],
                  "value":["--posix-only","yes"]}
               ],
               "jvm":["-Djava.library.path=${'$'}{natives_directory}","-cp","${'$'}{classpath}"]
             }}
        """)
        val plan = LaunchResolver.resolve(document, env())
        assertEquals("mc.Main", plan.mainClass)
        assertEquals(
            listOf("--username", "Dev") +
                if (OperatingSystem.current() != OperatingSystem.WINDOWS) {
                    listOf("--posix-only", "yes")
                } else emptyList(),
            plan.programArgs.filter { it != "--demo" }
        )
        assertTrue(plan.jvmArgs.any { it.startsWith("-Djava.library.path=") })
        assertTrue(plan.jvmArgs.any { it.contains("client.jar") })
    }

    @Test
    fun `legacy minecraftArguments split and substitute`() {
        val document = data("""
            {"id":"1.12.2","mainClass":"mc.legacy.Main",
             "minecraftArguments":"--username ${'$'}{auth_player_name} --uuid ${'$'}{auth_uuid}"}
        """)
        val session = OfflineAuth("Steve").session()
        val plan = LaunchResolver.resolve(document, env(session = session))
        assertEquals(
            listOf("--username", "Steve", "--uuid", session.uuid),
            plan.programArgs
        )
    }

    @Test
    fun `demo feature gates demo argument`() {
        val document = data("""
            {"id":"1.20.1","mainClass":"mc.Main",
             "arguments":{"game":[
               {"rules":[{"action":"allow","features":{"is_demo_user":true}}],
                "value":"--demo"}
             ]}}
        """)
        assertTrue(
            LaunchResolver.resolve(document, env()).programArgs.none { it == "--demo" }
        )
        assertTrue(
            LaunchResolver.resolve(document, env(features = setOf("is_demo_user")))
                .programArgs.contains("--demo")
        )
    }

    @Test
    fun `native coordinates select os classifier`() {
        val document = data("""
            {"id":"1.20.1",
             "libraries":[
               {"name":"org.lwjgl:lwjgl:3.3.1",
                "natives":{"linux":"natives-linux","windows":"natives-windows"}},
               {"name":"org.example:nonative:1.0"},
               {"name":"org.example:mac-only:1.0",
                "natives":{"osx":"natives-macos"},
                "rules":[{"action":"allow","os":{"name":"osx"}}]}
             ]}
        """)
        assertEquals(
            listOf("org.lwjgl:lwjgl:3.3.1:natives-linux"),
            Natives.coordinates(document, OperatingSystem.LINUX)
        )
        assertEquals(
            listOf("org.lwjgl:lwjgl:3.3.1:natives-windows"),
            Natives.coordinates(document, OperatingSystem.WINDOWS)
        )
        assertEquals(
            listOf("org.example:mac-only:1.0:natives-macos"),
            Natives.coordinates(document, OperatingSystem.OSX)
        )
    }

}
