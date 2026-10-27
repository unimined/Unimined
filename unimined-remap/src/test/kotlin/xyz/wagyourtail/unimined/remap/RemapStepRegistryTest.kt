package xyz.wagyourtail.unimined.remap

import org.gradle.api.file.FileSystemLocation
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import xyz.wagyourtail.unimined.api.spec.RemapSpec
import xyz.wagyourtail.unimined.api.spi.SpiSelectionException
import xyz.wagyourtail.unimined.remap.spi.ArtifactStep
import xyz.wagyourtail.unimined.remap.spi.RemapStepType
import xyz.wagyourtail.unimined.remap.spi.RemapSurface
import xyz.wagyourtail.unimined.remap.spi.StepContext
import xyz.wagyourtail.unimined.remap.spi.StepSpec
import kotlin.reflect.KClass

class RemapStepRegistryTest {

    private class FakeSpec(override val engine: String? = null) : StepSpec

    private class FakeStep(
        override val name: String,
        private val accepts: Boolean = true,
        override val surfaces: Set<RemapSurface> = setOf(RemapSurface.JAR)
    ) : RemapStepType<FakeSpec> {
        override val stepName = "fake"
        override val specType: KClass<FakeSpec> = FakeSpec::class
        override fun supports(spec: RemapSpec) = accepts
        override fun create(spec: FakeSpec, ctx: StepContext): ArtifactStep =
            ArtifactStep { input, _ -> input }
    }

    private val remapSpec = object : RemapSpec {
        override val sourceNamespace = "official"
        override val targetNamespace = "named"
        override val classpath: List<FileSystemLocation> = emptyList()
    }

    @Test
    fun `resolves the single supporting impl`() {
        val registry = RemapStepRegistry()
        val a = FakeStep("a")
        val b = FakeStep("b", accepts = false)
        registry.register(a)
        registry.register(b)

        assertSame(a, registry.resolve(FakeSpec(), RemapSurface.JAR, remapSpec))
    }

    @Test
    fun `engine override bypasses supports`() {
        val registry = RemapStepRegistry()
        val a = FakeStep("a", accepts = false)
        registry.register(a)

        assertSame(a, registry.resolve(FakeSpec(engine = "a"), RemapSurface.JAR, remapSpec))
    }

    @Test
    fun `unregistered spec type throws`() {
        val registry = RemapStepRegistry()
        assertThrows(SpiSelectionException::class.java) {
            registry.resolve(FakeSpec(), RemapSurface.JAR, remapSpec)
        }
    }

    @Test
    fun `ambiguous impls without override throw`() {
        val registry = RemapStepRegistry()
        registry.register(FakeStep("a"))
        registry.register(FakeStep("b"))

        assertThrows(SpiSelectionException::class.java) {
            registry.resolve(FakeSpec(), RemapSurface.JAR, remapSpec)
        }
    }

    @Test
    fun `impl without surface support is rejected`() {
        val registry = RemapStepRegistry()
        registry.register(FakeStep("a")) // JAR only

        // no impl registered for this spec *on this surface* — selection
        // fails with the surface named in the message
        val ex = assertThrows(SpiSelectionException::class.java) {
            registry.resolve(FakeSpec(), RemapSurface.SOURCES, remapSpec)
        }
        assertTrue(ex.message!!.contains("SOURCES"))
    }

}
