package xyz.wagyourtail.unimined.transform.forge

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import xyz.wagyourtail.unimined.transform.forge.internal.ForgeModAnalysis
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.Attributes
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import kotlin.io.path.outputStream

/**
 * Unit coverage for FML access-transformer discovery — the standard
 * `META-INF/accesstransformer.cfg` location plus the old-FML `FMLAT`
 * manifest attribute (1.x `ModRemapProvider` parity).
 */
class ForgeModAnalysisTest {

    @TempDir
    lateinit var dir: Path

    private fun jar(
        name: String,
        entries: Map<String, String> = emptyMap(),
        fmlat: String? = null
    ): Path {
        val jar = dir.resolve(name)
        if (fmlat != null) {
            val manifest = Manifest().apply {
                mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
                mainAttributes[Attributes.Name("FMLAT")] = fmlat
            }
            JarOutputStream(jar.outputStream(), manifest).use { out ->
                for ((path, text) in entries) {
                    out.putNextEntry(JarEntry(path))
                    out.write(text.toByteArray())
                    out.closeEntry()
                }
            }
        } else {
            JarOutputStream(jar.outputStream()).use { out ->
                for ((path, text) in entries) {
                    out.putNextEntry(JarEntry(path))
                    out.write(text.toByteArray())
                    out.closeEntry()
                }
            }
        }
        return jar
    }

    @Test
    fun `classTweakers finds META-INF accesstransformer`() {
        val jar = jar(
            "mod.jar", mapOf(
                ForgeModAnalysis.FORGE_AT to "public a/b/C m()V"
            )
        )
        assertEquals(
            mapOf(ForgeModAnalysis.FORGE_AT to "*"),
            ForgeModAnalysis.classTweakers(jar)
        )
    }

    @Test
    fun `classTweakers finds FMLAT manifest entries`() {
        val jar = jar(
            "old-mod.jar",
            mapOf("at.cfg" to "x", "custom_dir/second_at.cfg" to "y"),
            fmlat = "at.cfg custom_dir/second_at.cfg"
        )
        assertEquals(
            mapOf("at.cfg" to "*", "custom_dir/second_at.cfg" to "*"),
            ForgeModAnalysis.classTweakers(jar)
        )
    }

    @Test
    fun `classTweakers combines both sources without duplicates`() {
        val jar = jar(
            "both.jar",
            mapOf(ForgeModAnalysis.FORGE_AT to "x", "extra.cfg" to "y"),
            fmlat = "META-INF/accesstransformer.cfg extra.cfg"
        )
        assertEquals(
            mapOf(ForgeModAnalysis.FORGE_AT to "*", "extra.cfg" to "*"),
            ForgeModAnalysis.classTweakers(jar)
        )
    }

    @Test
    fun `classTweakers empty for plain jar`() {
        assertEquals(emptyMap<String, String>(), ForgeModAnalysis.classTweakers(jar("plain.jar")))
    }

    @Test
    fun `extractClassTweakers copies declared files to cache`() {
        val jar = jar(
            "extract.jar",
            mapOf(ForgeModAnalysis.FORGE_AT to "public a/b/C m()V", "at2.cfg" to "z"),
            fmlat = "at2.cfg missing.cfg"
        )
        val cache = dir.resolve("ct-cache")
        val extracted = ForgeModAnalysis.extractClassTweakers(jar, cache)
        assertEquals(2, extracted.size)
        assertEquals(
            "public a/b/C m()V",
            extracted.first { it.fileName.toString().endsWith("accesstransformer.cfg") }
                .let { Files.readString(it) }
        )
        // entries referenced by FMLAT but absent from the jar are skipped
        assertTrue(extracted.none { it.fileName.toString().endsWith("missing.cfg") })
    }

}
