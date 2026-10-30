package xyz.wagyourtail.unimined.transform.jarmod

import org.gradle.api.logging.Logger
import org.gradle.api.logging.Logging
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import xyz.wagyourtail.unimined.api.mapping.MappingData
import xyz.wagyourtail.unimined.transform.processor.JarProcessor.ModJarInfo
import xyz.wagyourtail.unimined.transform.jarmod.internal.fg1.FG1FixForgeProcessor
import xyz.wagyourtail.unimined.transform.processor.JarProcessor
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Env-gated smoke test of [FG1FixForgeProcessor] against real FG1-era
 * forge universal zips — `argo/` + `org/bouncycastle/` entries are
 * injected to stand in for the stale bundled copies that appear in
 * merged jars; each version's `CoreFMLLibraries` dyn-lib list decides
 * which prefixes get stripped.
 */
class RealFG1SmokeTest {

    @TempDir
    lateinit var dir: Path

    private val ctx = object : JarProcessor.ProcessorContext {
        override val logger: Logger = Logging.getLogger(RealFG1SmokeTest::class.java)
        override val mappings: MappingData? = null
        override fun mods(): List<ModJarInfo> = emptyList()
    }

    private fun withBundledLibs(universal: Path): Path {
        val out = dir.resolve("merged-${universal.fileName}")
        ZipOutputStream(Files.newOutputStream(out)).use { o ->
            ZipFile(universal.toFile()).use { z ->
                z.entries().asSequence().filter { !it.isDirectory }.forEach { e ->
                    o.putNextEntry(ZipEntry(e.name))
                    z.getInputStream(e).use { it.copyTo(o) }
                    o.closeEntry()
                }
            }
            for (name in listOf("argo/x.class", "org/bouncycastle/y.class")) {
                o.putNextEntry(ZipEntry(name)); o.write(1); o.closeEntry()
            }
        }
        return out
    }

    private fun names(jar: Path): Set<String> =
        ZipFile(jar.toFile()).use { z -> z.entries().asSequence().map { it.name }.toSet() }

    @Test
    fun `fixForge strips bundled libs per real dyn-lib lists`() {
        val zips = Path.of("/tmp/fg1-smoke")
        // version -> (argo stripped, bcprov stripped)
        val cases = listOf(
            "1.3.2-4.3.5.318" to (true to false),
            "1.4.7-6.6.2.534" to (true to true),
            "1.5.2-7.8.1.738" to (true to true),
        )
        for ((v, expected) in cases) {
            val universal = zips.resolve("forge-$v-universal.zip")
            assumeTrue(Files.exists(universal), "missing $universal")
            val merged = withBundledLibs(universal)
            val proc = FG1FixForgeProcessor()
            proc.processJar(merged, FG1FixForgeProcessor.Spec(true), ctx)
            val entries = names(merged)
            val (argo, bcprov) = expected
            if (argo) assertFalse(entries.any { it.startsWith("argo/") }, "$v argo left")
            else assertTrue(entries.any { it.startsWith("argo/") }, "$v argo stripped")
            if (bcprov) assertFalse(
                entries.any { it.startsWith("org/bouncycastle/") }, "$v bcprov left"
            ) else assertTrue(
                entries.any { it.startsWith("org/bouncycastle/") }, "$v bcprov stripped"
            )
            assertTrue(
                entries.any { it == "cpw/mods/fml/relauncher/CoreFMLLibraries.class" },
                "$v core class lost"
            )
        }
    }
}
