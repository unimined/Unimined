package xyz.wagyourtail.unimined.minecraft

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import xyz.wagyourtail.unimined.minecraft.localrepo.LocalMavenPublisher
import java.nio.file.Files
import java.nio.file.Path

class LocalMavenPublisherTest {

    @TempDir
    lateinit var dir: Path

    @Test
    fun `publishes pom plus classifier artifacts under synthetic coords`() {
        val jar = dir.resolve("dev.jar").also { Files.write(it, byteArrayOf(1)) }
        val src = dir.resolve("src.jar").also { Files.write(it, byteArrayOf(2)) }

        val repo = dir.resolve("repo")
        val moduleDir = LocalMavenPublisher.publish(
            repo, "unimined.generated", "minecraft-fabric", "1.20.1",
            mapOf("" to jar, "sources" to src)
        )

        val module = repo.resolve("unimined/generated/minecraft-fabric/1.20.1")
        assertEquals(module, moduleDir)
        assertTrue(Files.exists(module.resolve("minecraft-fabric-1.20.1.pom")))
        assertTrue(Files.exists(module.resolve("minecraft-fabric-1.20.1.jar")))
        assertTrue(Files.exists(module.resolve("minecraft-fabric-1.20.1-sources.jar")))
        assertTrue(
            Files.readString(module.resolve("minecraft-fabric-1.20.1.pom"))
                .contains("<artifactId>minecraft-fabric</artifactId>")
        )
    }

    @Test
    fun `re-publish of identical module does not rewrite outputs`() {
        val jar = dir.resolve("dev.jar").also { Files.write(it, byteArrayOf(1)) }
        val repo = dir.resolve("repo")
        LocalMavenPublisher.publish(
            repo, "unimined.generated", "minecraft-fabric", "1.20.1",
            mapOf("" to jar)
        )
        val module = repo.resolve("unimined/generated/minecraft-fabric/1.20.1")
        val pom = module.resolve("minecraft-fabric-1.20.1.pom")
        val out = module.resolve("minecraft-fabric-1.20.1.jar")
        val old = java.nio.file.attribute.FileTime.fromMillis(1_000)
        Files.setLastModifiedTime(pom, old)
        Files.setLastModifiedTime(out, old)

        // byte-identical inputs — a rewrite would still churn the repo
        // dir listing the configuration cache fingerprints (the atomic
        // write's transient `.*.tmp` invalidates the entry)
        LocalMavenPublisher.publish(
            repo, "unimined.generated", "minecraft-fabric", "1.20.1",
            mapOf("" to jar)
        )
        assertEquals(old, Files.getLastModifiedTime(pom))
        assertEquals(old, Files.getLastModifiedTime(out))
    }

    @Test
    fun `re-publish with changed jar refreshes the artifact`() {
        val jar = dir.resolve("dev.jar").also { Files.write(it, byteArrayOf(1)) }
        val jar2 = dir.resolve("dev2.jar").also { Files.write(it, byteArrayOf(1, 2)) }
        val repo = dir.resolve("repo")
        LocalMavenPublisher.publish(
            repo, "unimined.generated", "minecraft-fabric", "1.20.1",
            mapOf("" to jar)
        )
        LocalMavenPublisher.publish(
            repo, "unimined.generated", "minecraft-fabric", "1.20.1",
            mapOf("" to jar2)
        )
        val module = repo.resolve("unimined/generated/minecraft-fabric/1.20.1")
        assertEquals(
            listOf<Byte>(1, 2),
            Files.readAllBytes(module.resolve("minecraft-fabric-1.20.1.jar")).toList()
        )
    }

}
