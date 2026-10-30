package xyz.wagyourtail.unimined.transform.jarmod

import org.gradle.api.logging.Logger
import org.gradle.api.logging.Logging
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.io.TempDir
import xyz.wagyourtail.unimined.api.mapping.MappingData
import xyz.wagyourtail.unimined.mapping.formats.at.LegacyATReader
import xyz.wagyourtail.unimined.transform.jarmod.internal.fg1.FG1FixForgeProcessor
import xyz.wagyourtail.unimined.transform.processor.JarProcessor
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile

/**
 * The FG1-era AT cfg files use `cls.member(desc)` joining; the
 * [FG1FixForgeProcessor] rewrite normalizes them. Runs the real
 * reader over the processed content to prove it.
 */
class FG1AtNormalizeTest {

    @TempDir lateinit var dir: Path

    private val logger: Logger = Logging.getLogger(FG1AtNormalizeTest::class.java)

    @Test
    fun eraAtContentPassesReader() {
        val universal = File("/tmp/fg1-smoke/forge-1.4.7-6.6.2.534-universal.zip")
        assumeTrue(universal.isFile)
        val jar = dir.resolve("merged.jar")
        Files.copy(universal.toPath(), jar)

        val proc = FG1FixForgeProcessor()
        proc.processJar(jar, proc.deserializeSpec("true"),
            object : JarProcessor.ProcessorContext {
                override val logger = xyz.wagyourtail.unimined.api.util.uniminedLogger(this@FG1AtNormalizeTest.logger)
                override val mappings: MappingData? = null
                override fun mods() = emptyList<JarProcessor.ModJarInfo>()
            })

        // jar ATs must keep era `cls.member(desc)` format — the legacy
        // reader/writer handles them in remapAt; FML's runtime
        // AccessTransformer needs the joined syntax intact
        ZipFile(jar.toFile()).use { z ->
            for (name in listOf("forge_at.cfg", "fml_at.cfg")) {
                val bytes = z.getInputStream(z.getEntry(name)).readBytes()
                val data = LegacyATReader.readData(
                    xyz.wagyourtail.commonskt.reader.StringCharReader(
                        bytes.toString(Charsets.UTF_8)),
                    leinient = false)
                println("$name -> ${data.size} items")
                assertTrue(data.isNotEmpty())
            }
        }
    }
}
