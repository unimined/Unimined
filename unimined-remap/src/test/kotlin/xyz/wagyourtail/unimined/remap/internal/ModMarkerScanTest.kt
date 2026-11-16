package xyz.wagyourtail.unimined.remap.internal

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import kotlin.io.path.outputStream

/**
 * [ModMarkerScan] marker verdict + loom dep-jar manifest contract
 * (`Fabric-Loom-Remap`, `Fabric-Loom-Known-Indy-BSMS`,
 * `Fabric-Loom-Mixin-Remap-Type`, `Fabric-Mapping-Namespace`).
 */
class ModMarkerScanTest {

    @TempDir
    lateinit var dir: Path

    private fun jar(
        name: String,
        manifestAttrs: Map<String, String> = emptyMap(),
        entries: Map<String, String> = emptyMap()
    ): Path {
        val p = dir.resolve(name)
        JarOutputStream(p.outputStream(), Manifest().apply {
            mainAttributes.putValue("Manifest-Version", "1.0")
            manifestAttrs.forEach { (k, v) -> mainAttributes.putValue(k, v) }
        }).use { out ->
            entries.forEach { (entry, content) ->
                out.putNextEntry(JarEntry(entry))
                out.write(content.toByteArray())
                out.closeEntry()
            }
        }
        return p
    }

    @Test
    fun `fabric mod marker detected`() {
        val j = jar("mod.jar", entries = mapOf("fabric.mod.json" to "{}"))
        assertTrue(ModMarkerScan.isModJar(j, ModMarkerScan.DEFAULT_MARKERS))
    }

    @Test
    fun `plain library is not a mod`() {
        val j = jar("lib.jar", entries = mapOf("com/x/A.class" to "CAFEBABE"))
        val info = ModMarkerScan.scan(j, ModMarkerScan.DEFAULT_MARKERS)
        assertFalse(info.isMod)
        assertNull(info.loomRemap)
        assertTrue(info.knownIndyBsms.isEmpty())
    }

    @Test
    fun `net-minecraft reference marks unmarked jars`() {
        val j = jar("lib.jar", entries = mapOf(
            "com/x/A.class" to "references net/minecraft/world/Level"))
        assertTrue(ModMarkerScan.isModJar(j, ModMarkerScan.DEFAULT_MARKERS))
    }

    @Test
    fun `loom remap false opts out despite markers`() {
        val j = jar("mod.jar",
            manifestAttrs = mapOf("Fabric-Loom-Remap" to "false"),
            entries = mapOf("fabric.mod.json" to "{}"))
        val info = ModMarkerScan.scan(j, ModMarkerScan.DEFAULT_MARKERS)
        assertTrue(info.isMod) // marker still matches…
        assertEquals(false, info.loomRemap) // …but the directive wins
    }

    @Test
    fun `loom remap true forces remap of unmarked jar`() {
        val j = jar("lib.jar",
            manifestAttrs = mapOf("Fabric-Loom-Remap" to "true"),
            entries = mapOf("com/x/A.class" to "no refs here"))
        val info = ModMarkerScan.scan(j, ModMarkerScan.DEFAULT_MARKERS)
        assertFalse(info.isMod)
        assertEquals(true, info.loomRemap)
    }

    @Test
    fun `known indy bsms split on commas`() {
        val j = jar("mod.jar",
            manifestAttrs = mapOf(
                "Fabric-Loom-Known-Indy-BSMS" to
                        "org/codehaus/groovy/vmplugin/v8/IndyInterface," +
                        " scala/runtime/LambdaDeserializer ,",
            ),
            entries = mapOf("fabric.mod.json" to "{}"))
        assertEquals(
            listOf(
                "org/codehaus/groovy/vmplugin/v8/IndyInterface",
                "scala/runtime/LambdaDeserializer"
            ),
            ModMarkerScan.scan(j, ModMarkerScan.DEFAULT_MARKERS).knownIndyBsms
        )
    }

    @Test
    fun `mixin remap type and namespace hint captured`() {
        val j = jar("mod.jar",
            manifestAttrs = mapOf(
                "Fabric-Loom-Mixin-Remap-Type" to "STATIC",
                "Fabric-Mapping-Namespace" to "intermediary",
            ),
            entries = mapOf("fabric.mod.json" to "{}"))
        val info = ModMarkerScan.scan(j, ModMarkerScan.DEFAULT_MARKERS)
        assertEquals("STATIC", info.mixinRemapType)
        assertEquals("intermediary", info.namespaceHint)
    }

    @Test
    fun `jar without manifest still scans`() {
        // no MANIFEST.MF at all — JarOutputStream needs a manifest arg,
        // write raw zip instead
        val p = dir.resolve("bare.jar")
        java.util.zip.ZipOutputStream(p.outputStream()).use { out ->
            out.putNextEntry(java.util.zip.ZipEntry("fabric.mod.json"))
            out.write("{}".toByteArray())
            out.closeEntry()
        }
        val info = ModMarkerScan.scan(p, ModMarkerScan.DEFAULT_MARKERS)
        assertTrue(info.isMod)
        assertNull(info.loomRemap)
    }

}
