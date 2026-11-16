package xyz.wagyourtail.unimined.provider.legacydist

import org.gradle.api.logging.Logging
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import xyz.wagyourtail.unimined.api.spec.DeclaredSource
import xyz.wagyourtail.unimined.api.spec.DeclaredSourceSpec
import xyz.wagyourtail.unimined.api.spec.ProductSpec
import xyz.wagyourtail.unimined.api.spec.ProductVersionSpec
import xyz.wagyourtail.unimined.api.spec.Side
import xyz.wagyourtail.unimined.api.util.uniminedLogger
import xyz.wagyourtail.unimined.minecraft.spi.ProductArtifactProducerRegistry
import xyz.wagyourtail.unimined.minecraft.internal.RuntimeVersionSpec
import xyz.wagyourtail.unimined.minecraft.spi.ArtifactKey
import xyz.wagyourtail.unimined.minecraft.spi.ArtifactProductionRequest
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/** `legacydist` artifact producer — the transform-side twin of
 *  [LegacyDistProvider]. */
class LegacyDistArtifactProducerTest {

    @TempDir
    lateinit var projectDir: File

    private fun spec(source: DeclaredSource, format: String? = null) =
        object : ProductSpec {
            override val environmentId = "main"
            override val version: ProductVersionSpec =
                RuntimeVersionSpec("custom-1.0")
            override val side = Side.MERGED
            override val loader = null
            override val declaredSource =
                DeclaredSourceSpec(source, "official", format)
        }

    private fun request(cacheDir: Path) = ArtifactProductionRequest(
        cacheDir = cacheDir,
        artifactKey = ArtifactKey.MERGED,
        logger = uniminedLogger(
            Logging.getLogger(LegacyDistArtifactProducer::class.java)
        )
    )

    @Test
    fun `supports claims every declared-source spec`() {
        val producer = LegacyDistArtifactProducer()
        assertTrue(producer.supports(
            spec(DeclaredSource.LocalFile("x.jar"))))
        // the floor claim is priority-scoped, not supports-scoped —
        // `devbundle`-tagged specs still claim here but lose the
        // negotiation to that producer's marker claim
        assertTrue(producer.supports(
            spec(DeclaredSource.LocalFile("b.zip"), "devbundle")))
        assertFalse(producer.supports(object : ProductSpec {
            override val version = RuntimeVersionSpec("1.0")
            override val side = Side.CLIENT
            override val loader = null
        }))
    }

    @Test
    fun `selects through the producer registry`() {
        val registry = ProductArtifactProducerRegistry()
        val picked = registry.select(
            spec(DeclaredSource.LocalFile("x.jar")), "legacydist"
        )
        assertTrue(picked is LegacyDistArtifactProducer)
        // capability selection too — no other declared-source claimant
        // is registered on this module's classpath
        assertTrue(registry.select(
            spec(DeclaredSource.LocalFile("x.jar"))
        ) is LegacyDistArtifactProducer)
    }

    @Test
    fun `produces a declared local file as the raw artifact`() {
        val src = File(projectDir, "dist.jar")
            .apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        val cache = File(projectDir, "cache").toPath()
        val source = DeclaredSource.LocalFile(src.absolutePath)
        val out = LegacyDistArtifactProducer().produce(
            spec(source), request(cache)
        )
        assertEquals(byteArrayOf(1, 2, 3, 4).toList(),
            Files.readAllBytes(out).toList())
        // the descriptor-keyed cache location is shared with the
        // provider — whichever side runs first wins
        assertEquals(
            LegacyDistProduction.distDir(source, cache)
                .resolve("main-custom-1.0.jar"),
            out
        )
    }

    @Test
    fun `producer sources run inside the transform via ProcessBuilder`() {
        // a real subprocess — `java -cp <this classpath> WriteFileMain
        // out.jar`, mirroring `legacydist { producer(...) }`
        val java = Path.of(
            System.getProperty("java.home"), "bin", "java"
        ).toString()
        val out = LegacyDistArtifactProducer().produce(
            spec(DeclaredSource.Producer(
                executable = java,
                jvmArgs = listOf(
                    "-cp", System.getProperty("java.class.path")
                ),
                args = listOf(WriteFileMain::class.java.name, "out.jar"),
                output = "out.jar"
            )),
            request(File(projectDir, "cache").toPath())
        )
        assertEquals(byteArrayOf(42).toList(),
            Files.readAllBytes(out).toList())
    }

    @Test
    fun `producer failure names the captured log`() {
        val java = Path.of(
            System.getProperty("java.home"), "bin", "java"
        ).toString()
        val e = assertThrows(IllegalStateException::class.java) {
            LegacyDistArtifactProducer().produce(
                spec(DeclaredSource.Producer(
                    executable = java,
                    jvmArgs = listOf(
                        "-cp", System.getProperty("java.class.path")
                    ),
                    args = listOf("does.not.Exist"),
                    output = "out.jar"
                )),
                request(File(projectDir, "cache").toPath())
            )
        }
        assertTrue(e.message!!.contains("producer.log"))
    }

    @Test
    fun `maven source fails loudly`() {
        assertThrows(IllegalStateException::class.java) {
            LegacyDistArtifactProducer().produce(
                spec(DeclaredSource.Maven("g:a:1.0")),
                request(File(projectDir, "cache").toPath())
            )
        }
    }

    @Test
    fun `missing declaredSource fails`() {
        assertThrows(IllegalStateException::class.java) {
            LegacyDistArtifactProducer().produce(
                object : ProductSpec {
                    override val version = RuntimeVersionSpec("1.0")
                    override val side = Side.MERGED
                    override val loader = null
                },
                request(File(projectDir, "cache").toPath())
            )
        }
    }

}

/** `main` writes [args][0] relative to its working dir — the payload
 *  a `legacydist { producer(...) }` subprocess would emit. */
class WriteFileMain {
    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            File(args[0]).writeBytes(byteArrayOf(42))
        }
    }
}
