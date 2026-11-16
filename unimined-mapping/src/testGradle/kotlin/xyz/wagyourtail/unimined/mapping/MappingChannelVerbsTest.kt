package xyz.wagyourtail.unimined.mapping

import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Provider
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import xyz.wagyourtail.unimined.api.spec.Side
import java.nio.file.Path

/**
 * Parameterized coverage of the `mappings { }` channel sugar — every
 * verb is asserted against the *lazy* state of the contributed
 * [MappingsSpecImpl.Source] (notation provider / builtin channel +
 * params, `nsMap` relabels, `namedNamespaces`) without resolving a
 * single artifact.
 */
class MappingChannelVerbsTest {

    @TempDir
    lateinit var dir: Path

    private fun objects(): ObjectFactory = ProjectBuilder.builder()
        .withProjectDir(dir.toFile()).build().objects

    private fun provider(v: String): Provider<String> =
        objects().property(String::class.java).apply { set(v) }

    private fun spec(mc: String? = "1.20.1", side: Side? = null) =
        MappingsSpecImpl(
            objects(),
            mc?.let { provider(it) },
            side?.let { s ->
                objects().property(Side::class.java).apply { set(s) }
            }
        )

    private fun mavenSources(spec: MappingsSpecImpl) =
        spec.sources.filterIsInstance<MappingsSpecImpl.Source.Maven>()

    private fun mavenNotations(spec: MappingsSpecImpl) =
        mavenSources(spec).map { it.notation }

    private fun builtinSources(spec: MappingsSpecImpl) =
        spec.sources.filterIsInstance<MappingsSpecImpl.Source.Builtin>()

    data class MavenCase(
        val name: String,
        val declare: MappingsSpecImpl.() -> Unit,
        val notations: List<String>,
        val nsMap: Map<String, String> = emptyMap(),
        val named: Set<String> = emptySet()
    )

    companion object {

        @JvmStatic
        fun mavenChannels(): List<Arguments> = listOf(
            Arguments.of(MavenCase(
                "intermediary",
                { intermediary() },
                listOf("net.fabricmc:intermediary:1.20.1:v2")
            )),
            Arguments.of(MavenCase(
                "yarn int build",
                { yarn(10) },
                listOf("net.fabricmc:yarn:1.20.1+build.10:v2")
            )),
            Arguments.of(MavenCase(
                "yarn string build",
                { yarn("latest") },
                listOf("net.fabricmc:yarn:1.20.1+build.latest:v2")
            )),
            Arguments.of(MavenCase(
                "mcp",
                { mcp("stable", "39-1.12") },
                listOf("de.oceanlabs.mcp:mcp_stable:39-1.12@zip"),
                // srg docs label obf source/target; csvs label it
                // notch — all relabeled, mcp flagged named
                mapOf(
                    "source" to "official", "target" to "searge",
                    "notch" to "official"
                ),
                setOf("mcp")
            )),
            Arguments.of(MavenCase(
                "retroMCP explicit version",
                // a version absent from the mcphackers manifest passes
                // through artifactKey unchanged — deterministic offline
                { retroMCP("9.9.9-nonexistent") },
                listOf("io.github.mcphackers:mcp:9.9.9-nonexistent@zip"),
                // merged non-split env: the file's `client` column is
                // the one official space
                mapOf("named" to "retroMCP", "client" to "official"),
                setOf("retroMCP")
            )),
            Arguments.of(MavenCase(
                "retroMCP mc-version default",
                { retroMCP() },
                listOf("io.github.mcphackers:mcp:1.20.1@zip"),
                mapOf("named" to "retroMCP", "client" to "official"),
                setOf("retroMCP")
            )),
            Arguments.of(MavenCase(
                "feather int build gen2",
                { feather(4) },
                listOf("net.ornithemc:feather-gen2:1.20.1+build.4:v2"),
                mapOf(
                    "intermediary" to "calamusGen2",
                    "named" to "featherGen2"
                ),
                setOf("featherGen2")
            )),
            Arguments.of(MavenCase(
                "feather string build",
                { feather("custom") },
                listOf("net.ornithemc:feather:1.20.1+build.custom:v2")
            )),
            Arguments.of(MavenCase(
                "sparrow enricher",
                { sparrow(7) },
                listOf("net.ornithemc:sparrow:1.20.1+build.7"),
                mapOf("source" to "official")
            )),
            Arguments.of(MavenCase(
                "raven enricher",
                { raven(7) },
                listOf("net.ornithemc:raven:1.20.1+build.7"),
                mapOf("source" to "official")
            )),
            Arguments.of(MavenCase(
                "legacyYarn gen1",
                { legacyYarn(510, gen = 1) },
                listOf("net.legacyfabric:yarn:1.20.1+build.510:v2")
            )),
            Arguments.of(MavenCase(
                "legacyYarn gen2",
                { legacyYarn(510, gen = 2) },
                listOf("net.legacyfabric.v2:yarn:1.20.1+build.510:v2"),
                mapOf("named" to "legacyYarnGen2")
            )),
            Arguments.of(MavenCase(
                "barn",
                { barn(17) },
                listOf("babric:barn:1.20.1+build.17:v2")
            )),
            Arguments.of(MavenCase(
                "nostalgia int build",
                { nostalgia(3) },
                listOf("me.alphamode:nostalgia:1.20.1+build.3:v2"),
                mapOf("intermediary" to "babricIntermediary")
            )),
            Arguments.of(MavenCase(
                "nostalgia string build",
                { nostalgia("latest") },
                listOf("me.alphamode:nostalgia:1.20.1+build.latest:v2"),
                mapOf("intermediary" to "babricIntermediary")
            )),
            Arguments.of(MavenCase(
                "quilt",
                { quilt(12) },
                listOf("org.quiltmc:quilt-mappings:1.20.1+build.12:intermediary-v2")
            )),
            Arguments.of(MavenCase(
                "calamus gen2",
                { calamus() },
                listOf("net.ornithemc:calamus-intermediary-gen2:1.20.1:v2"),
                // merged non-split: one official space = the client col
                mapOf(
                    "intermediary" to "calamusGen2",
                    "clientOfficial" to "official"
                )
            )),
        )
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("mavenChannels")
    fun `maven channel contributes lazy coordinate and metadata`(
        case: MavenCase
    ) {
        val spec = spec()
        spec.run(case.declare)

        assertEquals(case.notations, mavenNotations(spec), case.name)
        for (source in mavenSources(spec)) {
            assertEquals(case.nsMap, source.nsMap, "${case.name} nsMap")
            assertEquals(case.named, source.namedNamespaces,
                "${case.name} namedNamespaces")
        }
        assertEquals(case.notations.size, spec.sources.size, case.name)
    }

    @Test
    fun `spigot declares the builtin channel with the env mc version`() {
        val spec = spec("1.20.1")
        spec.spigot()

        val source = builtinSources(spec).single()
        assertEquals("spigot", source.channel)
        assertEquals("1.20.1", source.params.getValue("version")())
    }

    @Test
    fun `forgeBuiltinMCP declares the builtin channel and named ns`() {
        val spec = spec("1.4.7")
        spec.forgeBuiltinMCP("6.6.2.534")

        val source = builtinSources(spec).single()
        assertEquals("forgeBuiltinMCP", source.channel)
        assertEquals("6.6.2.534", source.params.getValue("version")())
        assertEquals(setOf("forgeMCP"), source.namedNamespaces)
    }

    @Test
    fun `parchment overloads pin mcVersion version and checked`() {
        // explicit mcVersion + checked
        spec("ignored").run {
            parchment("1.20.1", "2023.09.03", checked = true)
            val explicit = builtinSources(this).single()
            assertEquals("parchment", explicit.channel)
            assertEquals("1.20.1",
                explicit.params.getValue("mcVersion")())
            assertEquals("2023.09.03",
                explicit.params.getValue("version")())
            assertEquals("true", explicit.params.getValue("checked")())
        }
        // env-defaulted mcVersion, unchecked by default
        spec("1.20.4").run {
            parchment("2024.02.25")
            val defaulted = builtinSources(this).single()
            assertEquals("1.20.4",
                defaulted.params.getValue("mcVersion")())
            assertEquals("2024.02.25",
                defaulted.params.getValue("version")())
            assertEquals("false", defaulted.params.getValue("checked")())
        }
    }

    @Test
    fun `gen1 feather on a split-era merged env declares both sides`() {
        val spec = spec("b1.7.3", Side.MERGED)
        spec.feather(4, gen = 1)

        val notations = mavenNotations(spec)
        assertEquals(
            listOf(
                "net.ornithemc:feather:b1.7.3-client+build.4:v2",
                "net.ornithemc:feather:b1.7.3-server+build.4:v2"
            ),
            notations
        )
        // both per-side officials union into the shared calamus hop
        assertTrue(spec.splitOfficial)
        assertEquals(
            listOf("official:client", "official:server"),
            spec.splitOfficialNamespaces
        )
        for (source in mavenSources(spec)) {
            assertEquals("calamus", source.nsMap["intermediary"])
            assertEquals("feather", source.nsMap["named"])
            assertEquals(setOf("feather"), source.namedNamespaces)
        }
    }

    @Test
    fun `gen1 calamus env-tags per-side official columns`() {
        val spec = spec("b1.7.3", Side.MERGED)
        spec.calamus(gen = 1)

        val sources = mavenSources(spec)
        assertEquals(2, sources.size)
        assertEquals(
            listOf(
                "net.ornithemc:calamus-intermediary:b1.7.3-client:v2",
                "net.ornithemc:calamus-intermediary:b1.7.3-server:v2"
            ),
            sources.map { it.notation }
        )
        assertEquals("calamus", spec.splitMergeNamespace)
        assertTrue(spec.splitOfficial)
        // each file's `official` src column carries its env tag
        assertEquals("official:client", sources[0].nsMap["official"])
        assertEquals("official:server", sources[1].nsMap["official"])
        for (source in sources) {
            assertEquals("calamus", source.nsMap["intermediary"])
        }
    }

    @Test
    fun `gen1 feather on a client env contributes the client artifact only`() {
        val spec = spec("b1.7.3", Side.CLIENT)
        spec.feather(4, gen = 1)

        val source = mavenSources(spec).single()
        assertEquals(
            "net.ornithemc:feather:b1.7.3-client+build.4:v2",
            source.notation
        )
        // no env tag — the file is already side-scoped
        assertEquals(
            mapOf("intermediary" to "calamus", "named" to "feather"),
            source.nsMap
        )
        assertFalse(spec.splitOfficial)
    }

    @Test
    fun `enricher sources follow the env side`() {
        val spec = spec("b1.7.3", Side.SERVER)
        spec.nests(3)
        spec.sparrow(3)
        spec.raven(3)

        assertEquals(
            listOf(
                "net.ornithemc:nests:b1.7.3-server+build.3",
                "net.ornithemc:sparrow:b1.7.3-server+build.3",
                "net.ornithemc:raven:b1.7.3-server+build.3"
            ),
            mavenNotations(spec)
        )
        for (source in mavenSources(spec)) {
            assertEquals(mapOf("source" to "official"), source.nsMap)
        }
    }

    @Test
    fun `retroMCP relabels both side columns on a client env`() {
        val spec = spec("b1.7.3", Side.CLIENT)
        spec.retroMCP("9.9.9-nonexistent")

        val source = mavenSources(spec).single()
        // sided env: the file's client/server dst columns tag
        // per-side official so envView folds the env's own column
        assertEquals("official:client", source.nsMap["client"])
        assertEquals("official:server", source.nsMap["server"])
        assertEquals("retroMCP", source.nsMap["named"])
        assertEquals(setOf("retroMCP"), source.namedNamespaces)
    }

    @Test
    fun `mc-version-backed verbs name the channel when unset`() {
        val detached = spec(mc = null)
        fun check(name: String, verb: MappingsSpecImpl.() -> Unit) {
            val e = assertThrows(IllegalArgumentException::class.java) {
                detached.verb()
            }
            assertTrue(e.message!!.contains(name), "$name: ${e.message}")
        }
        check("intermediary") { intermediary() }
        check("yarn") { yarn(1) }
        check("feather") { feather(1) }
        check("calamus") { calamus() }
        check("spigot") { spigot() }
        check("parchment") { parchment("1.0") }
    }

}
