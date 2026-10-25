package xyz.wagyourtail.unimined.provider.native.metadata

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import xyz.wagyourtail.unimined.minecraft.spi.ArtifactKey
import xyz.wagyourtail.unimined.provider.native.manifest.VersionJson

class MinecraftMetadataAssemblerTest {

    @Test
    fun `projects downloads libraries assets and raw launch metadata`() {
        val client = VersionJson.Download("https://example/client.jar", "c1", 10)
        val server = VersionJson.Download("https://example/server.jar", "s1", 20)
        val index = VersionJson.Download("https://example/assets.json", "a1", 30, "legacy")
        val version = VersionJson(
            id = "1.20.1",
            type = "release",
            downloads = mapOf("client" to client, "server" to server),
            assetIndex = index,
            libraries = listOf(
                VersionJson.Library("org.example:common:1.0"),
                VersionJson.Library(
                    "org.example:windows-only:1.0",
                    rules = listOf(
                        VersionJson.Rule("allow", VersionJson.OsRule(name = "windows"))
                    )
                )
            )
        )
        val root = Json.parseToJsonElement("{}") as kotlinx.serialization.json.JsonObject

        val assembled = MinecraftMetadataAssembler.assemble(
            version = version,
            root = root,
            platform = PlatformInfo("windows", "amd64", "10.0")
        )

        assertSame(client, assembled.downloads[ArtifactKey.CLIENT])
        assertSame(server, assembled.downloads[ArtifactKey.SERVER])
        assertNull(assembled.downloads[ArtifactKey.MERGED])
        assertSame(index, assembled.assetIndex)
        assertEquals(
            listOf("org.example:common:1.0", "org.example:windows-only:1.0"),
            assembled.libraries
        )
        assertNull(assembled.launch)
    }

    @Test
    fun `ordered rules and features determine library applicability`() {
        val version = VersionJson(
            id = "1.20.1",
            type = "release",
            libraries = listOf(
                VersionJson.Library(
                    "org.example:allow-all-except-mac:1",
                    rules = listOf(
                        VersionJson.Rule("allow"),
                        VersionJson.Rule("disallow", VersionJson.OsRule(name = "osx"))
                    )
                ),
                VersionJson.Library(
                    "org.example:feature:1",
                    rules = listOf(
                        VersionJson.Rule(
                            "allow",
                            features = mapOf("is_demo_user" to true)
                        )
                    )
                )
            )
        )
        val root = Json.parseToJsonElement("{}") as kotlinx.serialization.json.JsonObject

        val mac = MinecraftMetadataAssembler.assemble(
            version, root, PlatformInfo("osx", "aarch64", "14.0")
        )
        val windows = MinecraftMetadataAssembler.assemble(
            version,
            root,
            PlatformInfo("windows", "amd64", "10.0", mapOf("is_demo_user" to true))
        )

        assertEquals(emptyList<String>(), mac.libraries)
        assertEquals(
            listOf("org.example:allow-all-except-mac:1", "org.example:feature:1"),
            windows.libraries
        )
    }

}
