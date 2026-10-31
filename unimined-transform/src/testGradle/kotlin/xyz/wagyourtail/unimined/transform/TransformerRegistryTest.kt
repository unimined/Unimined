package xyz.wagyourtail.unimined.transform

import org.gradle.api.Project
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import xyz.wagyourtail.unimined.transform.loader.LoaderDescriptor
import xyz.wagyourtail.unimined.transform.spi.MinecraftTransformer
import xyz.wagyourtail.unimined.transform.spi.TransformContext
import xyz.wagyourtail.unimined.transform.spi.TransformerClaim
import xyz.wagyourtail.unimined.transform.spi.TransformerRegistry
import xyz.wagyourtail.unimined.transform.task.TransformMinecraftTask
import java.nio.file.Path

class TransformerRegistryTest {

    @TempDir
    lateinit var dir: Path

    private val project: Project by lazy {
        ProjectBuilder.builder().withProjectDir(dir.toFile()).build()
    }
    private val env by lazy {
        TransformEnvironmentSpec("test", project.path, project.objects)
    }

    private inner class FakeTransformer(val tag: String) : MinecraftTransformer {
        override val transformTaskProvider =
            project.tasks.register("t_$tag", TransformMinecraftTask::class.java)
        override fun configure(ctx: TransformContext) {}
    }

    private fun claim(
        tag: String,
        families: Set<String> = emptySet(),
        fallback: Boolean = false
    ) = object : TransformerClaim {
        override val claimedFamilies = families
        override val fallbackClaim = fallback
        override fun transformer(env: TransformerClaim.TransformerEnvContext) =
            FakeTransformer(tag)
    }

    private fun ctx(descriptor: LoaderDescriptor?) =
        object : TransformerClaim.TransformerEnvContext {
            override val project = this@TransformerRegistryTest.project
            override val descriptor = descriptor
            override val env = this@TransformerRegistryTest.env
        }

    private fun descriptor(family: String) = LoaderDescriptor(
        id = "${family}Loader",
        family = family,
        mavens = emptyList(),
        loaderCoords = { g, v -> "$g:$v" },
        prodNamespace = "official",
    )

    private fun tagOf(t: MinecraftTransformer?) = (t as FakeTransformer).tag

    @Test
    fun `family claim beats fallback`() {
        val reg = TransformerRegistry()
        reg.register(claim(tag = "custom", fallback = true))
        reg.register(claim(tag = "fabric", families = setOf("fabric")))
        assertEquals("fabric", tagOf(reg.select(ctx(descriptor("fabric")))))
    }

    @Test
    fun `fallback claims rowless env`() {
        val reg = TransformerRegistry()
        reg.register(claim(tag = "custom", fallback = true))
        assertEquals("custom", tagOf(reg.select(ctx(null))))
    }

    @Test
    fun `fallback claims unclaimed family`() {
        val reg = TransformerRegistry()
        reg.register(claim(tag = "fabric", families = setOf("fabric")))
        reg.register(claim(tag = "custom", fallback = true))
        assertEquals("custom", tagOf(reg.select(ctx(descriptor("quilt")))))
    }

    @Test
    fun `nothing claims without fallback`() {
        val reg = TransformerRegistry()
        reg.register(claim(tag = "fabric", families = setOf("fabric")))
        assertNull(reg.select(ctx(descriptor("quilt"))))
        assertNull(reg.select(ctx(null)))
    }

    @Test
    fun `env explicit push bypasses registry`() {
        // explicit transformers() push wins — the registry is never
        // consulted (effectiveTransformers early-returns)
        val explicit = FakeTransformer("explicit")
        env.transformer(explicit)
        assertEquals(listOf(explicit), env.effectiveTransformers())
    }

}
