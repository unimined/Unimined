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

}
