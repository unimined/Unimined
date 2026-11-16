package xyz.wagyourtail.unimined.provider.legacydist

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import xyz.wagyourtail.unimined.api.spec.DeclaredSource
import java.nio.file.Path
import java.util.Locale
import kotlin.io.path.createParentDirectories
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes

/** `LegacyDistProduction`: Gradle-free materialization semantics —
 *  both the provider and the artifact-transform producer delegate
 *  here, so the seams (download/runProducer) are faked rather than
 *  exercised through Gradle. */
class LegacyDistProductionTest {

    @TempDir
    lateinit var dir: Path

    private val noDownload: (String, String, String?) -> Path =
        { url, _, _ -> error("unexpected download of $url") }

    @Test
    fun `distDir is content-keyed per source`() {
        val a = LegacyDistProduction.distDir(
            DeclaredSource.LocalFile("a.jar"), dir
        )
        val a2 = LegacyDistProduction.distDir(
            DeclaredSource.LocalFile("a.jar"), dir
        )
        val b = LegacyDistProduction.distDir(
            DeclaredSource.LocalFile("b.jar"), dir
        )
        assertEquals(a, a2)
        assertNotEquals(a, b)
    }

    @Test
    fun `local file materializes into the descriptor-keyed cache`() {
        val src = dir.resolve("src.jar").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val source = DeclaredSource.LocalFile(src.toString())
        val out = LegacyDistProduction.materialize(
            source, "env-1.0.jar", dir,
            download = noDownload,
            runProducer = null,
            rebuild = false
        ) {}
        assertEquals(
            LegacyDistProduction.distDir(source, dir).resolve("env-1.0.jar"),
            out
        )
        assertEquals(byteArrayOf(1, 2, 3).toList(), out.readBytes().toList())
    }

    @Test
    fun `relative local file resolves against the project dir`() {
        dir.resolve("rel.jar").writeBytes(byteArrayOf(4))
        val out = LegacyDistProduction.materialize(
            DeclaredSource.LocalFile("rel.jar"), "e.jar", dir,
            download = noDownload,
            runProducer = null,
            rebuild = false,
            projectDir = dir
        ) {}
        assertEquals(byteArrayOf(4).toList(), out.readBytes().toList())
    }

    @Test
    fun `missing local file fails`() {
        assertThrows(IllegalStateException::class.java) {
            LegacyDistProduction.materialize(
                DeclaredSource.LocalFile(dir.resolve("nope.jar").toString()),
                "e.jar", dir,
                download = noDownload,
                runProducer = null,
                rebuild = false
            ) {}
        }
    }

    @Test
    fun `url routes through the download seam with a normalized pin`() {
        var gotName: String? = null
        var gotSha: String? = null
        val pin = "AB12".repeat(16) // 64-hex sha256, uppercase
        val out = LegacyDistProduction.materialize(
            DeclaredSource.Url("https://x/dist.jar", pin),
            "env.jar", dir,
            download = { _, name, sha ->
                gotName = name
                gotSha = sha
                dir.resolve(name).apply {
                    createParentDirectories()
                    writeBytes(byteArrayOf(9))
                }
            },
            runProducer = null,
            rebuild = false
        ) {}
        assertTrue(gotName!!.startsWith("legacydist/"))
        assertTrue(gotName!!.endsWith("/env.jar"))
        // Downloader compares digests case-sensitively — the pin is
        // normalized to lowercase before it leaves this object
        assertEquals(pin.lowercase(Locale.ROOT), gotSha)
        assertEquals(byteArrayOf(9).toList(), out.readBytes().toList())
    }

    @Test
    fun `unpinned url warns but still fetches`() {
        val warns = mutableListOf<String>()
        LegacyDistProduction.materialize(
            DeclaredSource.Url("https://x/d.jar"), "e.jar", dir,
            download = { _, name, _ ->
                dir.resolve(name).apply {
                    createParentDirectories()
                    writeBytes(byteArrayOf(1))
                }
            },
            runProducer = null,
            rebuild = false,
            warn = warns::add
        )
        assertTrue(warns.single().contains("no sha256 pin"))
    }

    @Test
    fun `producer source runs the runner and publishes declared output`() {
        val source = DeclaredSource.Producer(
            "tool", listOf("a"), listOf("-Xmx1g"), "out.jar"
        )
        var ranIn: Path? = null
        val out = LegacyDistProduction.materialize(
            source, "env.jar", dir,
            download = noDownload,
            runProducer = { src, work ->
                ranIn = work
                work.resolve(src.output).writeBytes(byteArrayOf(7))
            },
            rebuild = false
        ) {}
        // the runner's work dir IS the descriptor-keyed cache dir —
        // the declared output is moved out of it onto the jar path
        assertEquals(LegacyDistProduction.distDir(source, dir), ranIn)
        assertEquals(byteArrayOf(7).toList(), out.readBytes().toList())
    }

    @Test
    fun `producer without a runner fails loudly`() {
        assertThrows(IllegalStateException::class.java) {
            LegacyDistProduction.materialize(
                DeclaredSource.Producer("tool", output = "o.jar"),
                "e.jar", dir,
                download = noDownload,
                runProducer = null,
                rebuild = false
            ) {}
        }
    }

    @Test
    fun `producer that emits nothing fails`() {
        assertThrows(IllegalStateException::class.java) {
            LegacyDistProduction.materialize(
                DeclaredSource.Producer("tool", output = "o.jar"),
                "e.jar", dir,
                download = noDownload,
                runProducer = { _, _ -> },
                rebuild = false
            ) {}
        }
    }

    @Test
    fun `maven source is rejected`() {
        assertThrows(IllegalStateException::class.java) {
            LegacyDistProduction.materialize(
                DeclaredSource.Maven("g:a:1.0"), "e.jar", dir,
                download = noDownload,
                runProducer = null,
                rebuild = false
            ) {}
        }
    }

}
