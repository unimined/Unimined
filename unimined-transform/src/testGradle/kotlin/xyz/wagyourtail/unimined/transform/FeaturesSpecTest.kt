package xyz.wagyourtail.unimined.transform

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.Assertions.assertEquals
import xyz.wagyourtail.unimined.transform.feature.EnvironmentFeature
import xyz.wagyourtail.unimined.transform.feature.FeatureRegistry

class FeaturesSpecTest {

    private val ctx = object : EnvironmentFeature.FeatureContext {
        override val bindingValue: String? = null
        override val side: String? = "client"
        override val family: String? = "fabric"
    }

    @Test
    fun `known bundled keys resolve`() {
        val spec = FeaturesSpec(FeatureRegistry())
        spec.binding("mixin")
        spec.binding("nestedJars", "fabric")
        spec.binding("modDiscovery", "dli")
        assertEquals(3, spec.resolve(ctx).size)
    }

    @Test
    fun `unknown key fails at declaration`() {
        val spec = FeaturesSpec(FeatureRegistry())
        assertThrows<IllegalArgumentException> {
            spec.binding("noSuchFeature")
        }
    }

    @Test
    fun `invalid binding value fails at selection`() {
        val spec = FeaturesSpec(FeatureRegistry())
        spec.binding("nestedJars", "bogus")
        assertThrows<IllegalStateException> {
            spec.resolve(ctx)
        }
    }

    @Test
    fun `modDiscovery rejects unknown mechanism`() {
        val spec = FeaturesSpec(FeatureRegistry())
        spec.binding("modDiscovery", "bogus")
        assertThrows<IllegalStateException> {
            spec.resolve(ctx)
        }
    }

    @Test
    fun `registered impl shadows bundled key`() {
        val reg = FeatureRegistry()
        val custom = object : EnvironmentFeature {
            override val id = "mixin"
            override val surfaces = setOf(EnvironmentFeature.Surface.RUN_CONFIG)
        }
        reg.register(custom)
        assertEquals(custom, reg.resolve("mixin"))
    }

}
