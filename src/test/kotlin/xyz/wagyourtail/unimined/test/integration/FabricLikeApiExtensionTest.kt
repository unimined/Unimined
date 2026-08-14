package xyz.wagyourtail.unimined.test.integration

import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.MethodSource
import xyz.wagyourtail.unimined.api.minecraft.patch.fabric.FabricLikeApiExtension
import xyz.wagyourtail.unimined.util.createFakeProject
import kotlin.test.assertEquals

class FabricLikeApiExtensionTest {
    companion object {
        lateinit var extension: FabricLikeApiExtension

        @JvmStatic
        @BeforeAll
        fun init() {
            extension = FabricLikeApiExtension(createFakeProject())
        }

        @JvmStatic
        fun generateOSLGen1Modules(): Array<Arguments> = arrayOf(
            Arguments.of("0.16.0", "1.12.2", setOf(
                "branding:0.3.2+mc16w05b-mc1.12.2",
                "config:0.5.2+mc15w40a-mc1.12.2",
                "core:0.6.0",
                "entrypoints:0.4.3+mc13w16a-04192037-mc1.14.4",
                "keybinds:0.1.3+mc17w16a-mc1.12.2",
                "lifecycle-events:0.5.4+mc13w36a-09051446-mc1.13",
                "networking:0.8.0+mc14w31a-mc1.13-pre2",
                "resource-loader:0.5.3+mc16w32a-mc1.12.2"
            )),
            Arguments.of("0.16.0", "1.8.9", setOf(
                "branding:0.3.2+mc14w30a-mc16w05a",
                "config:0.5.2+mc14w27a-mc15w39c",
                "core:0.6.0",
                "entrypoints:0.4.3+mc13w16a-04192037-mc1.14.4",
                "keybinds:0.1.3+mc13w36a-09051446-mc17w15a",
                "lifecycle-events:0.5.4+mc13w36a-09051446-mc1.13",
                "networking:0.8.0+mc14w31a-mc1.13-pre2",
                "resource-loader:0.5.3+mc13w26a-mc1.10.2"
            )),
            Arguments.of("0.16.0", "1.3.2", setOf(
                "branding:0.3.2+mc1.3-pre-07261249-mc1.5.2",
                "config:0.5.2+mc1.3-pre-07261249-mc1.5.2",
                "core:0.6.0",
                "entrypoints:0.4.3+mc1.3-pre-07261249-mc1.5.2",
                "keybinds:0.1.3+mc1.3.1-mc1.5.2",
                "lifecycle-events:0.5.4+mc1.3-pre-07261249-mc1.5.2",
                "networking:0.8.0+mc1.3-pre-07261249-mc1.5.2",
                "resource-loader:0.5.3+mc1.3-pre-07261249-mc13w07a"
            ))
        )

        @JvmStatic
        fun generateOSLGen1SidedModules(): Array<Arguments> = arrayOf(
            Arguments.of("0.16.0", "1.2.5", "client", setOf(
                "branding:0.3.2+client-mca1.2.2-1624-mc12w17a",
                "core:0.6.0",
                "entrypoints:0.4.3+client-mca1.0.6-mc12w30e",
                "lifecycle-events:0.5.4+client-mcb1.8-pre1-201109081459-mc12w17a",
                "networking:0.8.0+client-mc11w49a-mc12w17a",
                "resource-loader:0.5.3+client-mc11w49a-mc1.2.5"
            )),
            Arguments.of("0.16.0", "1.2.5", "server", setOf(
                "core:0.6.0",
                "entrypoints:0.4.3+server-mcserver-a0.1.0-mc12w30e",
                "lifecycle-events:0.5.4+server-mc12w01a-mc12w17a",
                "networking:0.8.0+server-mc11w49a-mc12w16a"
            )),

            Arguments.of("0.16.0", "b1.7.3", "client", setOf(
                "branding:0.3.2+client-mca1.2.2-1624-mc12w17a",
                "config:0.5.2+client-mcb1.3-1750-mcb1.7.3",
                "core:0.6.0",
                "entrypoints:0.4.3+client-mca1.0.6-mc12w30e",
                "lifecycle-events:0.5.4+client-mcb1.3-1750-mcb1.7.3",
                "networking:0.8.0+client-mcb1.5-mc11w48a",
                "resource-loader:0.5.3+client-mca1.2.2-1624-mc11w48a"
            )),
            Arguments.of("0.16.0", "b1.7.3", "server", setOf(
                "core:0.6.0",
                "entrypoints:0.4.3+server-mcserver-a0.1.0-mc12w30e",
                "lifecycle-events:0.5.4+server-mcb1.4-1507-mc11w50a",
                "networking:0.8.0+server-mcb1.5-mc11w48a"
            )),
        )

        @JvmStatic
        fun generateOSLGen2Modules(): Array<Arguments> = arrayOf(
            Arguments.of("0.17.1", "1.12.2", setOf(
                "branding:0.4.1+mc16w05b-mc1.12.2",
                "config:0.6.0+mc15w40a-mc1.12.2",
                "core:0.7.0",
                "entrypoints:0.5.0+mc13w16a-mc1.14.4",
                "keybinds:0.2.0+mc17w16a-mc1.12.2",
                "lifecycle-events:0.6.0+mc13w36a-mc1.13",
                "networking:0.9.0+mc13w41a-mc18w30b",
                "networking-impl:0.1.0+mc14w31a-mc1.13-pre2",
                "resource-loader:0.6.0+mc16w32a-mc1.12.2"
            )),
            Arguments.of("0.17.1", "1.8.9", setOf(
                "branding:0.4.1+mc14w30a-mc16w05a",
                "config:0.6.0+mc14w27a-mc15w39c",
                "core:0.7.0",
                "entrypoints:0.5.0+mc13w16a-mc1.14.4",
                "keybinds:0.2.0+mc13w36a-mc17w15a",
                "lifecycle-events:0.6.0+mc13w36a-mc1.13",
                "networking:0.9.0+mc13w41a-mc18w30b",
                "networking-impl:0.1.0+mc14w31a-mc1.13-pre2",
                "resource-loader:0.6.0+mc13w26a-mc1.10.2"
            )),
            Arguments.of("0.17.1", "1.3.2", setOf(
                "branding:0.4.1+mca1.2.2-mc14w29b",
                "config:0.6.0+mc1.3-mc1.6.4",
                "core:0.7.0",
                "entrypoints:0.5.0+mca1.0.1_01-mc1.5.2",
                "keybinds:0.2.0+mcb1.8-pre1-mc1.6.4",
                "lifecycle-events:0.6.0+mc12w21a-mc1.6.4",
                "networking:0.9.0+mcb1.0-mc13w39b",
                "networking-impl:0.1.0+mc12w21a-mc13w39b",
                "resource-loader:0.6.0+mc12w15a-mc13w07a"
            ))
        )

        @JvmStatic
        fun generateOSLGen2SidedModules(): Array<Arguments> = arrayOf(
            Arguments.of("0.17.1", "1.2.5", setOf(
                "branding:0.4.1+mca1.2.2-mc14w29b",
                "core:0.7.0",
                "entrypoints:0.5.0+mca1.0.1_01-mc1.5.2",
                "keybinds:0.2.0+mcb1.8-pre1-mc1.6.4",
                "lifecycle-events:0.6.0+mc12w01a-mc12w17a",
                "networking:0.9.0+mcb1.0-mc13w39b",
                "networking-impl:0.1.0+mc11w49a-mc12w16a",
                "resource-loader:0.6.0+mc11w49a-mc1.2.5"
            )),
            Arguments.of("0.17.1", "b1.7.3", setOf(
                "branding:0.4.1+mca1.2.2-mc14w29b",
                "config:0.6.0+mcb1.3-mcb1.7.3",
                "core:0.7.0",
                "entrypoints:0.5.0+mca1.0.1_01-mc1.5.2",
                "keybinds:0.2.0+mca1.0.1_01-mcb1.7.3",
                "lifecycle-events:0.6.0+mcb1.3-mcb1.7.3",
                "networking:0.9.0+mcb1.0-mc13w39b",
                "networking-impl:0.1.0+mcb1.5-mc11w48a",
                "resource-loader:0.6.0+mca1.2.2-mc11w48a"
            ))
        )
    }

    @ParameterizedTest(name = "fabric API full {0}")
    @CsvSource(
        "0.100.8+1.21",
        "0.42.0+1.16",
        "0.28.5+1.14"
    )
    fun fabricFull(version: String) {
        val result = extension.fabric(version)
        assertEquals("net.fabricmc.fabric-api:fabric-api:$version", result)
    }

    @ParameterizedTest(name = "fabric API {0} module {1}")
    @CsvSource(
        "0.100.8+1.21,  fabric-command-api-v2,      2.2.28+6ced4dd9d1",
        "0.42.0+1.16,   fabric-entity-events-v1,    1.2.4+3cc0f0907d",
        "0.28.5+1.14,   fabric-events-lifecycle-v0, 0.2.1+4ea4772942"
    )
    fun fabricModule(apiVersion: String, module: String, moduleVersion: String) {
        val result = extension.fabricModule(module, apiVersion)
        assertEquals("net.fabricmc.fabric-api:$module:$moduleVersion", result)
    }

    @ParameterizedTest(name = "quilted fabric API full {0}")
    @CsvSource(
        "11.0.0-alpha.3+0.100.7-1.21",
        "6.0.0-beta.11+0.87.0-1.19.4",
        "1.0.0-beta.28+0.67.0-1.18.2"
    )
    fun quiltFabricFull(version: String) {
        val result = extension.quiltFabric(version)
        assertEquals("org.quiltmc.quilted-fabric-api:quilted-fabric-api:$version", result)
    }

    @ParameterizedTest(name = "quilted fabric API {0} module {2}")
    @CsvSource(
        "11.0.0-alpha.3+0.100.7-1.21, quilted-fabric-api,   fabric-data-attachment-api-v1,  11.0.0-alpha.3+0.100.7-1.21",
        "6.0.0-beta.11+0.87.0-1.19.4, quilted-fabric-api,   fabric-item-api-v1,             6.0.0-beta.11+0.87.0-1.19.4",
        "6.0.0-beta.11+0.87.0-1.19.4, qsl.block,            block_extensions,               5.0.0-beta.11+1.19.4",
        "1.0.0-beta.28+0.67.0-1.18.2, quilted-fabric-api,   fabric-convention-tags-v1,      1.0.0-beta.28+0.67.0-1.18.2",
        "1.0.0-beta.28+0.67.0-1.18.2, qsl.core,             resource_loader,                1.1.0-beta.26+1.18.2"
    )
    fun quiltFabricModule(apiVersion: String, moduleParent: String, module: String, moduleVersion: String) {
        val result = extension.quiltFabricModule(module, apiVersion)
        assertEquals("org.quiltmc.$moduleParent:$module:$moduleVersion", result)
    }

    @ParameterizedTest(name = "qsl full {0}")
    @CsvSource(
        "10.0.0-alpha.1+1.21",
        "5.0.0-beta.11+1.19.4",
        "1.1.0-beta.26+1.18.2"
    )
    fun qslFull(version: String) {
        val result = extension.qsl(version)
        assertEquals("org.quiltmc:qsl:$version", result)
    }

    @ParameterizedTest(name = "qsl {0} module {1}")
    @CsvSource(
        "10.0.0-alpha.1+1.21,   block,      10.0.0-alpha.1+1.21",
        "5.0.0-beta.11+1.19.4,  item,       5.0.0-beta.11+1.19.4",
        "5.0.0-beta.11+1.19.4,  rendering,  5.0.0-beta.11+1.19.4",
        "1.1.0-beta.26+1.18.2,  data,       1.1.0-beta.26+1.18.2",
        "1.1.0-beta.26+1.18.2,  misc,       1.1.0-beta.26+1.18.2"
    )
    fun qslModule(apiVersion: String, module: String, moduleVersion: String) {
        val result = extension.qslModule(module, apiVersion)
        assertEquals("org.quiltmc.qsl:$module:$moduleVersion", result)
    }

    @ParameterizedTest(name = "LFAPI full {0}")
    @CsvSource(
        "1.10.2+1.6.4",
        "1.7.1+1.12.2",
        "1.0.0+1.8.9"
    )
    fun legacyFabricFull(version: String) {
        val result = extension.legacyFabric(version)
        assertEquals("net.legacyfabric.legacy-fabric-api:legacy-fabric-api:$version", result)
    }

    @ParameterizedTest(name = "LFAPI {0} module {1}")
    @CsvSource(
        "1.10.2+1.6.4,  legacy-fabric-keybindings-api-v1,   1.1.1+1.6.4+281301eab14f",
        "1.7.1+1.12.2,  legacy-fabric-item-groups-v1,       2.0.0+1.12.2+ae4aa0d052",
        "1.0.0+1.8.9,   legacy-fabric-lifecycle-events-v1,  1.0.0+uncommited"
    )
    fun legacyFabricModule(apiVersion: String, module: String, moduleVersion: String) {
        val result = extension.legacyFabricModule(module, apiVersion)
        assertEquals("net.legacyfabric.legacy-fabric-api:$module:$moduleVersion", result)
    }

    @ParameterizedTest(name = "StationAPI full {0}")
    @CsvSource(
        "2.0-alpha.2.1, releases",
        "2.0-alpha.1,   releases",
        "6143ce4,       snapshots",
        "2988528,       snapshots"
    )
    fun stationFull(version: String, branch: String) {
        val result = extension.station(branch, version)
        assertEquals("net.modificationstation:StationAPI:$version", result)
    }

    @ParameterizedTest(name = "StationAPI {0} module {3}")
    @CsvSource(
        "2.0-alpha.2.1,     releases,   submodule.station-maths-v0, station-maths-v0,               2.0-alpha.2.1-1.0.0",
        "2.0-alpha.1,       releases,   2.0-alpha.1,                station-blockitems-v0,          2.0-alpha.1-1.0.0",
        "6143ce4,           snapshots,  6143ce4,                    station-lifecycle-events-v0,    2.0-PRE2-1.0.0",
        "2988528,           snapshots,  2988528,                    station-dimensions-v0,          2.0-PRE2-1.0.0"
    )
    fun stationModule(apiVersion: String, branch: String, moduleParent: String, module: String, moduleVersion: String) {
        val result = extension.stationModule(branch, module, apiVersion)
        assertEquals("net.modificationstation.StationAPI.$moduleParent:$module:$moduleVersion", result)
    }

    @ParameterizedTest(name = "OSL Gen1 {0} module {2} For MC {1}")
    @CsvSource(
        "0.16.0, 1.12.2, entrypoints, 0.4.3+mc13w16a-04192037-mc1.14.4",
        "0.16.0, 1.3.2, entrypoints, 0.4.3+mc1.3-pre-07261249-mc1.5.2"
    )
    fun oslGen1Module(apiVersion: String, mcVersion: String, module: String, moduleVersion: String) {
        val result = extension.oslModule(mcVersion, module, apiVersion)
        assertEquals("net.ornithemc.osl:$module:$moduleVersion", result)
    }

    @ParameterizedTest(name = "OSL Gen1 {0} module {3} For MC {1}-{2}")
    @CsvSource(
        "0.16.0, 1.2.5, client, entrypoints, 0.4.3+client-mca1.0.6-mc12w30e",
        "0.16.0, 1.2.5, server, entrypoints, 0.4.3+server-mcserver-a0.1.0-mc12w30e"
    )
    fun oslGen1SidedModule(apiVersion: String, mcVersion: String, environment: String, module: String, moduleVersion: String) {
        val result = extension.oslModule(mcVersion, module, apiVersion, environment)
        assertEquals("net.ornithemc.osl:$module:$moduleVersion", result)
    }

    @ParameterizedTest(name = "OSL Gen1 {0} for MC {1}")
    @MethodSource("generateOSLGen1Modules")
    fun oslGen1(apiVersion: String, mcVersion: String, expectedModules: Set<String>) {
        val modules = extension.osl(mcVersion, apiVersion)
        assertEquals(expectedModules.map { "net.ornithemc.osl:$it" }.toSet(), modules)
    }

    @ParameterizedTest(name = "OSL Gen1 {0} for MC {1}-{2}")
    @MethodSource("generateOSLGen1SidedModules")
    fun oslGen1Sided(apiVersion: String, mcVersion: String, environment: String, expectedModules: Set<String>) {
        val modules = extension.osl(mcVersion, apiVersion, environment)
        assertEquals(expectedModules.map { "net.ornithemc.osl:$it" }.toSet(), modules)
    }

    @ParameterizedTest(name = "OSL Gen2 {0} module {2} For MC {1}")
    @CsvSource(
        "0.17.1, 1.12.2, entrypoints, 0.5.0+mc13w16a-mc1.14.4",
        "0.17.1, 1.3.2, entrypoints, 0.5.0+mca1.0.1_01-mc1.5.2"
    )
    fun oslGen2Module(apiVersion: String, mcVersion: String, module: String, moduleVersion: String) {
        val result = extension.oslModule(2, mcVersion, module, apiVersion)
        assertEquals("net.ornithemc.osl-gen2:$module:$moduleVersion", result)
    }

    @ParameterizedTest(name = "OSL Gen2 {0} module {2} For MC {1}")
    @CsvSource(
        "0.17.1, 1.2.5, entrypoints, 0.5.0+mca1.0.1_01-mc1.5.2",
        "0.17.1, b1.7.3, entrypoints, 0.5.0+mca1.0.1_01-mc1.5.2"
    )
    fun oslGen2SidedModule(apiVersion: String, mcVersion: String, module: String, moduleVersion: String) {
        val result = extension.oslModule(2, mcVersion, module, apiVersion)
        assertEquals("net.ornithemc.osl-gen2:$module:$moduleVersion", result)
    }

    @ParameterizedTest(name = "OSL Gen2 {0} for MC {1}")
    @MethodSource("generateOSLGen2Modules")
    fun oslGen2(apiVersion: String, mcVersion: String, expectedModules: Set<String>) {
        val modules = extension.osl(2, mcVersion, apiVersion)
        assertEquals(expectedModules.map { "net.ornithemc.osl-gen2:$it" }.toSet(), modules)
    }

    @ParameterizedTest(name = "OSL Gen2 {0} for MC {1}")
    @MethodSource("generateOSLGen2SidedModules")
    fun oslGen2Sided(apiVersion: String, mcVersion: String, expectedModules: Set<String>) {
        val modules = extension.osl(2, mcVersion, apiVersion)
        assertEquals(expectedModules.map { "net.ornithemc.osl-gen2:$it" }.toSet(), modules)
    }
}