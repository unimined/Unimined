package xyz.wagyourtail.unimined.transform.forge.fakes

import xyz.wagyourtail.unimined.api.spec.ProductSpec
import xyz.wagyourtail.unimined.minecraft.spi.ArtifactProductionRequest
import xyz.wagyourtail.unimined.minecraft.spi.ProductArtifactProducer
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream

/**
 * Transform-time counterpart to [FakeNamedProvider]: selected only by the
 * explicit `provider("fake")` override and emits a minimal valid jar, so
 * wiring tests can resolve artifact-transform chains without network-backed
 * Minecraft artifacts.
 */
class FakeArtifactProducer : ProductArtifactProducer {

    override val name = "fake"

    override fun supports(spec: ProductSpec) = false

    override fun produce(spec: ProductSpec, request: ArtifactProductionRequest): Path {
        val out = request.cacheDir.resolve(
            "fake-artifact-producer/${spec.environmentId}/" +
                request.artifactKey.name.lowercase() + ".jar"
        )
        Files.createDirectories(out.parent)
        JarOutputStream(Files.newOutputStream(out)).use { jar ->
            jar.putNextEntry(JarEntry("fake-artifact.txt"))
            jar.write(spec.environmentId.toByteArray())
            jar.closeEntry()
        }
        return out
    }
}
