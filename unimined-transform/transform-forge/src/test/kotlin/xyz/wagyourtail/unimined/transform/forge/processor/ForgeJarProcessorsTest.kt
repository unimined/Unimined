package xyz.wagyourtail.unimined.transform.forge.processor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import xyz.wagyourtail.commons.core.logger.JavaLogWrapper
import xyz.wagyourtail.unimined.api.mapping.MappingData
import xyz.wagyourtail.unimined.transform.processor.JarProcessor
import java.nio.file.Path
import java.util.jar.Attributes
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import java.util.zip.ZipFile
import kotlin.io.path.outputStream

/**
 * The forge `jar-processors` family — manifest mutation, userdev
 * `inject/` copy, and universal shading — over synthetic jars. Each
 * processor mutates in place via zipfs, so assertions read the same
 * jar back.
 */
class ForgeJarProcessorsTest {

    @TempDir
    lateinit var dir: Path

    private val ctx = object : JarProcessor.ProcessorContext {
        override val logger = JavaLogWrapper(
            java.util.logging.Logger.getLogger("forge-processors-test"))
        override val mappings: MappingData? = null
        override fun mods() = emptyList<JarProcessor.ModJarInfo>()
    }

    private fun jar(
        name: String, entries: Map<String, ByteArray>,
        manifest: Manifest? = null
    ): Path {
        val out = dir.resolve(name)
        val stream = if (manifest != null)
            JarOutputStream(out.outputStream(), manifest)
        else JarOutputStream(out.outputStream())
        stream.use { jos ->
            for ((entryName, bytes) in entries) {
                jos.putNextEntry(JarEntry(entryName))
                jos.write(bytes)
                jos.closeEntry()
            }
        }
        return out
    }

    private fun entries(jar: Path): Map<String, ByteArray> =
        ZipFile(jar.toFile()).use { z ->
            z.entries().toList().filter { !it.isDirectory }.associate {
                it.name to z.getInputStream(it).readBytes()
            }
        }

    private fun manifestOf(jar: Path): Manifest =
        JarFile(jar.toFile()).use { it.manifest }

    private fun manifest(vararg attrs: Pair<String, String>) =
        Manifest().apply {
            mainAttributes.putValue("Manifest-Version", "1.0")
            attrs.forEach { (k, v) -> mainAttributes.putValue(k, v) }
        }

    // ---------- AutomaticModuleNameProcessor ----------

    @Test
    fun `automatic-module-name writes into a manifestless jar`() {
        val target = jar("mc.jar", mapOf("a/A.class" to byteArrayOf(1)))
        AutomaticModuleNameProcessor().processJar(
            target, AutomaticModuleNameProcessor.Spec("minecraft"), ctx)
        val mf = manifestOf(target)
        assertEquals("minecraft",
            mf.mainAttributes.getValue("Automatic-Module-Name"))
        assertEquals("1.0",
            mf.mainAttributes.getValue(Attributes.Name.MANIFEST_VERSION))
        assertTrue("a/A.class" in entries(target))
    }

    @Test
    fun `automatic-module-name merges into an existing manifest`() {
        val target = jar("mc.jar", mapOf("a/A.class" to byteArrayOf(1)),
            manifest("Main-Class" to "mc.Launch",
                "Automatic-Module-Name" to "old-name"))
        AutomaticModuleNameProcessor().processJar(
            target, AutomaticModuleNameProcessor.Spec("minecraft"), ctx)
        val mf = manifestOf(target)
        assertEquals("minecraft",
            mf.mainAttributes.getValue("Automatic-Module-Name"))
        assertEquals("mc.Launch", mf.mainAttributes.getValue("Main-Class"))
    }

    @Test
    fun `automatic-module-name no-ops when already correct`() {
        val target = jar("mc.jar", mapOf("a/A.class" to byteArrayOf(1)),
            manifest("Automatic-Module-Name" to "minecraft"))
        AutomaticModuleNameProcessor().processJar(
            target, AutomaticModuleNameProcessor.Spec("minecraft"), ctx)
        assertTrue(entries(target).containsKey("META-INF/MANIFEST.MF"))
    }

    @Test
    fun `manifestDonor contributes version sections minus digests`() {
        val donorMf = Manifest().apply {
            mainAttributes.putValue("Manifest-Version", "1.0")
            entries["net/minecraftforge/versions/forge/"] = Attributes().apply {
                putValue("Specification-Version", "47.3.0")
                putValue("Implementation-Version", "47.3.0")
                putValue("SHA-256-Digest", "stale-hash")
            }
            // sections without version attrs are not donated
            entries["net/minecraftforge/other/"] = Attributes().apply {
                putValue("X-Custom", "1")
            }
        }
        val donor = jar("universal.jar", emptyMap(), donorMf)
        val target = jar("mc.jar", mapOf("a/A.class" to byteArrayOf(1)))
        AutomaticModuleNameProcessor().processJar(target,
            AutomaticModuleNameProcessor.Spec("minecraft",
                manifestDonor = donor.toString()), ctx)
        val mf = manifestOf(target)
        val donated = mf.entries["net/minecraftforge/versions/forge/"]
        assertEquals("47.3.0", donated?.getValue("Specification-Version"))
        assertEquals("47.3.0", donated?.getValue("Implementation-Version"))
        assertNull(donated?.getValue("SHA-256-Digest"),
            "digest attrs hash pre-remap bytes — must be dropped")
        assertNull(mf.entries["net/minecraftforge/other/"])
    }

    @Test
    fun `automatic-module-name spec payload round-trips`() {
        val p = AutomaticModuleNameProcessor()
        val s = AutomaticModuleNameProcessor.Spec("minecraft", "/x/y.jar")
        assertEquals(s, p.deserializeSpec(p.serializeSpec(s)))
        val bare = AutomaticModuleNameProcessor.Spec("minecraft")
        assertEquals(bare, p.deserializeSpec(p.serializeSpec(bare)))
    }

    // ---------- UserdevInjectProcessor ----------

    @Test
    fun `userdev-inject copies prefixed entries with the prefix stripped`() {
        val installer = jar("installer.jar", mapOf(
            "inject/mcpmod.info" to "mod".toByteArray(),
            "inject/net/minecraftforge/versions/forge/ForgeVersion.class" to
                    byteArrayOf(2),
            "unrelated/x.class" to byteArrayOf(9)))
        val target = jar("mc.jar", mapOf("a/A.class" to byteArrayOf(1)))
        UserdevInjectProcessor().processJar(target,
            UserdevInjectProcessor.Spec(installer, "inject/"), ctx)
        val out = entries(target)
        assertEquals("mod", out["mcpmod.info"]?.toString(Charsets.UTF_8))
        assertTrue(out.containsKey(
            "net/minecraftforge/versions/forge/ForgeVersion.class"))
        assertFalse(out.containsKey("unrelated/x.class"),
            "non-inject entries must not leak")
        assertTrue(out.containsKey("a/A.class"))
    }

    @Test
    fun `userdev-inject replaces colliding entries`() {
        val installer = jar("installer.jar", mapOf(
            "inject/mcpmod.info" to "new".toByteArray()))
        val target = jar("mc.jar",
            mapOf("mcpmod.info" to "old".toByteArray()))
        UserdevInjectProcessor().processJar(target,
            UserdevInjectProcessor.Spec(installer, "inject/"), ctx)
        assertEquals("new",
            entries(target)["mcpmod.info"]?.toString(Charsets.UTF_8))
    }

    @Test
    fun `userdev-inject no-ops without inject entries`() {
        val installer = jar("installer.jar",
            mapOf("other.class" to byteArrayOf(1)))
        val target = jar("mc.jar", mapOf("a/A.class" to byteArrayOf(1)))
        UserdevInjectProcessor().processJar(target,
            UserdevInjectProcessor.Spec(installer, "inject/"), ctx)
        assertEquals(setOf("a/A.class"), entries(target).keys)
    }

    @Test
    fun `userdev-inject spec payload round-trips`() {
        val p = UserdevInjectProcessor()
        val s = UserdevInjectProcessor.Spec(
            Path.of("/tmp/installer.jar"), "inject/")
        val back = p.deserializeSpec(p.serializeSpec(s)) as
                UserdevInjectProcessor.Spec
        assertEquals(s.prefix, back.prefix)
        assertEquals(s.jar.toAbsolutePath(), back.jar.toAbsolutePath())
    }

    // ---------- UserdevUniversalShadeProcessor ----------

    private fun universalJar() = jar("universal.jar", mapOf(
        "META-INF/MANIFEST.MF" to "x".toByteArray(),
        "META-INF/TEST.SF" to "x".toByteArray(),
        "META-INF/mods.toml" to "toml".toByteArray(),
        "META-INF/accesstransformer.cfg" to "at".toByteArray(),
        "META-INF/services/x.y.Z" to "svc".toByteArray(),
        "binpatches.pack.lzma" to "pack".toByteArray(),
        "module-info.class" to byteArrayOf(3),
        "net/minecraftforge/fml/common/Mod.class" to byteArrayOf(4)))

    @Test
    fun `universal shade overlays loader classes and keeps runtime meta-inf`() {
        val target = jar("mc.jar", mapOf("a/A.class" to byteArrayOf(1)))
        UserdevUniversalShadeProcessor().processJar(target,
            UserdevUniversalShadeProcessor.Spec(universalJar(), emptyList()),
            ctx)
        val out = entries(target)
        assertTrue(out.containsKey("net/minecraftforge/fml/common/Mod.class"))
        // runtime-load-bearing META-INF survives the signature strip
        assertEquals("toml", out["META-INF/mods.toml"]?.toString(Charsets.UTF_8))
        assertEquals("at", out["META-INF/accesstransformer.cfg"]
            ?.toString(Charsets.UTF_8))
        assertEquals("svc", out["META-INF/services/x.y.Z"]
            ?.toString(Charsets.UTF_8))
        // signature debris + patch packs + module-info never shade
        for (excluded in listOf("META-INF/MANIFEST.MF", "META-INF/TEST.SF",
            "binpatches.pack.lzma", "module-info.class")) {
            assertFalse(out.containsKey(excluded), "$excluded shaded")
        }
        assertTrue(out.containsKey("a/A.class"))
    }

    @Test
    fun `universalFilters include-regex gates what shades`() {
        val target = jar("mc.jar", emptyMap())
        UserdevUniversalShadeProcessor().processJar(target,
            UserdevUniversalShadeProcessor.Spec(universalJar(),
                listOf("net/.*")), ctx)
        val out = entries(target)
        assertTrue(out.containsKey("net/minecraftforge/fml/common/Mod.class"))
        assertFalse(out.containsKey("META-INF/mods.toml"),
            "non-matching entries must be filtered out")
    }

    @Test
    fun `universal shade loader entries win collisions`() {
        val universal = jar("universal.jar", mapOf(
            "a/A.class" to "loader".toByteArray()))
        val target = jar("mc.jar", mapOf("a/A.class" to "base".toByteArray()))
        UserdevUniversalShadeProcessor().processJar(target,
            UserdevUniversalShadeProcessor.Spec(universal, emptyList()), ctx)
        assertEquals("loader",
            entries(target)["a/A.class"]?.toString(Charsets.UTF_8))
    }

    @Test
    fun `universal shade spec payload round-trips`() {
        val p = UserdevUniversalShadeProcessor()
        val s = UserdevUniversalShadeProcessor.Spec(
            Path.of("/tmp/u.jar"), listOf("a", "b.*"))
        val back = p.deserializeSpec(p.serializeSpec(s)) as
                UserdevUniversalShadeProcessor.Spec
        assertEquals(s.filters, back.filters)
        assertEquals(s.jar.toAbsolutePath(), back.jar.toAbsolutePath())
    }

}
