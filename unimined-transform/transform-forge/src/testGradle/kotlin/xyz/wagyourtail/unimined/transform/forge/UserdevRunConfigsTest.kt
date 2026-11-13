package xyz.wagyourtail.unimined.transform.forge

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import xyz.wagyourtail.unimined.transform.forge.internal.ForgeProfile
import xyz.wagyourtail.unimined.transform.forge.internal.UserdevConfig
import xyz.wagyourtail.unimined.transform.forge.internal.isFmlRuntimeDeobfEra
import xyz.wagyourtail.unimined.transform.forge.internal.userdev.UserdevRunConfigs

class UserdevRunConfigsTest {

    private fun cfg(runs: String, extra: String = "") = UserdevConfig(
        Json.parseToJsonElement(
            """{"runs": {$runs}$extra}"""
        ) as JsonObject
    )

    private val groups = mapOf(
        ":sub:main" to listOf("/p/sub/resources", "/p/sub/classes"),
        ":main" to listOf("/p/resources"),
    )

    // group key flattened (: -> _), each dir prefixed key%%dir,
    // resources before classes, pathSeparator-joined
    private val groupsValue = groups.entries.joinToString(
        java.io.File.pathSeparator
    ) { (k, dirs) ->
        dirs.joinToString(java.io.File.pathSeparator) {
            "${k.replace(":", "_")}%%$it"
        }
    }

    private val ctx = UserdevRunConfigs.Ctx(
        assetIndex = { "17" },
        groups = { groups },
        srgToMcpAsSrg = { "/cache/srg.srg" },
    )

    @Test
    fun `missing side returns null`() {
        assertNull(
            UserdevRunConfigs.forSide(
                cfg(""), "client", "FMLTweaker", ctx
            )
        )
    }

    @Test
    fun `legacydev shape uses launchwrapper + FML tweaker`() {
        val spec = UserdevRunConfigs.forSide(
            cfg(""""client": {"main": "net.minecraftforge.legacydev.MainClient"}"""),
            "client", "net.minecraftforge.fml.common.launcher.FMLTweaker", ctx
        )!!
        assertTrue(spec.legacyDev)
        assertEquals("net.minecraft.launchwrapper.Launch", spec.mainClass)
        assertEquals(
            listOf(
                "--tweakClass",
                "net.minecraftforge.fml.common.launcher.FMLTweaker"
            ), spec.args
        )
        assertTrue(
            spec.jvmArgs.contains(
                "-Dnet.minecraftforge.gradle.GradleStart.srg.srg-mcp=/cache/srg.srg"
            )
        )
        assertEquals(groupsValue, spec.environment["MOD_CLASSES"])
    }

    @Test
    fun `modern shape templates args env and props`() {
        val spec = UserdevRunConfigs.forSide(
            cfg(
                """"client": {
                "main": "net.minecraft.client.main.Main",
                "args": ["--assetsDir", "{assets}", "--assetIndex", "{asset_index}"],
                "jvmArgs": ["-cp", "{classpath}"],
                "env": {"tweakClass": "forge.DevLaunch", "FOO": "{version}"},
                "props": {"mcp": "{mcp_mappings}"}
            }""", """, "spec": 3"""
            ),
            "client", "FMLTweaker", ctx
        )!!
        assertEquals("net.minecraft.client.main.Main", spec.mainClass)
        assertEquals("forge.DevLaunch", spec.tweakClass)
        assertEquals(
            listOf(
                "--assetsDir", "\${assets}",
                "--assetIndex", "17"
            ), spec.args
        )
        assertEquals(
            listOf(
                "-cp", "\${classpath}",
                "-Dmcp=unimined.stub"
            ), spec.jvmArgs
        )
        assertEquals("3", spec.environment["FORGE_SPEC"])
        assertEquals("\${version}", spec.environment["FOO"])
        assertEquals(groupsValue, spec.environment["MOD_CLASSES"])
    }

    @Test
    fun `bootstrap-dev lt2_1_4 uses semicolon separator`() {
        val spec = UserdevRunConfigs.forSide(
            cfg(""""client": {"main": "net.minecraft.Main"}"""),
            "client", "FMLTweaker",
            UserdevRunConfigs.Ctx(
                groups = { groups },
                groupSeparator = ";"
            )
        )!!
        assertEquals(
            groupsValue.replace(
                java.io.File.pathSeparator, ";"
            ),
            spec.environment["MOD_CLASSES"]
        )
    }

    @Test
    fun `legacydev prod swaps deobf-assertion flags for runtime deobf`() {
        val spec = UserdevRunConfigs.forSide(
            cfg(""""client": {"main": "net.minecraftforge.legacydev.MainClient"}"""),
            "client", "FMLTweaker", ctx, prod = true
        )!!
        assertTrue(
            "-Dunimined.fg2.runtimeDeobf" in spec.jvmArgs,
            "prod launch keeps FML's runtime obf→srg deobf registered"
        )
        assertTrue(
            spec.jvmArgs.none {
                it.startsWith("-Dfml.deobfuscatedEnvironment")
            },
            "prod launch must not assert an already-deobfuscated jar"
        )
        assertTrue(
            spec.jvmArgs.none { "srg-mcp" in it },
            "the srg→mcp table is dev-mode runtime remapping"
        )
    }

    @Test
    fun `legacydev profiles are the FML runtime-deobf era`() {
        val legacy = ForgeProfile.Userdev(
            cfg(""""client": {"main": "net.minecraftforge.legacydev.MainClient"}""")
        )
        val modern = ForgeProfile.Userdev(
            cfg(""""client": {"main": "net.minecraft.client.main.Main"}""")
        )
        assertTrue(legacy.isFmlRuntimeDeobfEra())
        assertFalse(modern.isFmlRuntimeDeobfEra())
        assertFalse((null as ForgeProfile?).isFmlRuntimeDeobfEra())
    }
}
