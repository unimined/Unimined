package xyz.wagyourtail.unimined.transform.forge

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import xyz.wagyourtail.unimined.mod.LoaderSection
import xyz.wagyourtail.unimined.mod.ModMetadata
import xyz.wagyourtail.unimined.mod.spi.ModMetadataEmitter
import xyz.wagyourtail.unimined.transform.forge.internal.ForgeModMetadataEmitter
import xyz.wagyourtail.unimined.transform.forge.internal.NeoforgeModMetadataEmitter
import java.io.ByteArrayInputStream
import java.util.jar.Manifest

/**
 * Era routing for forge-family metadata: `[[mixins]]` in the TOML is
 * NeoForge-only (20.3+); every other forge-family shape declares mixin
 * configs through the jar manifest's `MixinConfigs` attribute, which
 * also carries `FMLModType` and legacy-era `FMLAT`.
 */
class ForgeModMetadataEmitterTest {

    private fun meta(loader: String, block: LoaderSection.() -> Unit = {}) =
        ModMetadata(
            id = "testmod", name = null, version = null,
            description = null, authors = emptyList(), license = null,
            url = null, icon = null, environment = null,
            depends = emptyMap(),
            sections = mapOf(loader to LoaderSection().apply(block))
        )

    private fun ctx(
        loader: String,
        mc: String = "1.20.1",
        loaderVersion: String? = null,
        accessFiles: List<String> = emptyList(),
        manifestAttributes: Map<String, String> = emptyMap(),
        block: LoaderSection.() -> Unit = {}
    ) = ModMetadataEmitter.EmitContext(
        metadata = meta(loader, block),
        mixinFiles = listOf("testmod.mixins.json"),
        environmentId = "main",
        minecraftVersion = mc,
        loaderVersion = loaderVersion,
        manifestAttributes = manifestAttributes,
        accessFiles = accessFiles,
        side = "CLIENT",
        devNamespace = "named",
        prodNamespaces = listOf("official")
    )

    private fun emittedManifest(
        out: Map<String, ByteArray>
    ): Manifest? = out[ModMetadataEmitter.MANIFEST_PATH]
        ?.let { Manifest(ByteArrayInputStream(it)) }

    @Test
    fun `forge declares mixins via manifest attr, never toml table`() {
        val out = ForgeModMetadataEmitter().emit(ctx("forge"))
        val toml = out["META-INF/mods.toml"]!!.toString(Charsets.UTF_8)
        assertFalse(toml.contains("[[mixins]]"),
            "mods.toml must not carry [[mixins]] — Forge ignores it:\n$toml")
        val mf = emittedManifest(out)!!
        assertEquals("testmod.mixins.json",
            mf.mainAttributes.getValue("MixinConfigs"))
    }

    @Test
    fun `neoforge 20_3+ uses toml mixins without the manifest attr`() {
        val out = NeoforgeModMetadataEmitter().emit(
            ctx("neoforge", loaderVersion = "20.4.239"))
        val toml = out["META-INF/neoforge.mods.toml"]!!
            .toString(Charsets.UTF_8)
        assertTrue(toml.contains("[[mixins]]"))
        assertTrue(toml.contains("config=\"testmod.mixins.json\""))
        val mf = emittedManifest(out)
        assertNull(mf?.mainAttributes?.getValue("MixinConfigs"),
            "toml-supported loader must not duplicate MixinConfigs attr")
    }

    @Test
    fun `neoforge before 20_3 falls back to the manifest attr`() {
        val out = NeoforgeModMetadataEmitter().emit(
            ctx("neoforge", loaderVersion = "20.2.43-beta"))
        val toml = out["META-INF/neoforge.mods.toml"]!!
            .toString(Charsets.UTF_8)
        assertFalse(toml.contains("[[mixins]]"))
        val mf = emittedManifest(out)!!
        assertEquals("testmod.mixins.json",
            mf.mainAttributes.getValue("MixinConfigs"))
    }

    @Test
    fun `fmlModType stamps the jar manifest`() {
        val out = ForgeModMetadataEmitter().emit(
            ctx("forge") { fmlModType = "LANGPROVIDER" })
        val mf = emittedManifest(out)!!
        assertEquals("LANGPROVIDER",
            mf.mainAttributes.getValue("FMLModType"))
    }

    @Test
    fun `legacy era lists non-canonical access files under FMLAT`() {
        val out = ForgeModMetadataEmitter().emit(
            ctx("forge", mc = "1.12.2",
                accessFiles = listOf(
                    "META-INF/accesstransformer.cfg",
                    "META-INF/custom_at.cfg")))
        val mf = emittedManifest(out)!!
        val fmlat = mf.mainAttributes.getValue("FMLAT")!!
        assertTrue(fmlat.contains("META-INF/custom_at.cfg"))
        assertFalse(fmlat.contains("accesstransformer.cfg"),
            "canonical path is auto-detected — FMLAT lists extras only")
    }

    @Test
    fun `env manifestAttributes merge into the manifest body`() {
        val out = ForgeModMetadataEmitter().emit(
            ctx("forge",
                manifestAttributes = mapOf("Custom-Attr" to "x")))
        val mf = emittedManifest(out)!!
        assertEquals("x", mf.mainAttributes.getValue("Custom-Attr"))
    }
}
