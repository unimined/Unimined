package xyz.wagyourtail.unimined

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import xyz.wagyourtail.unimined.api.testing.GroovyTwinParity

/**
 * Groovy-DSL parity regression guard — every public DSL method taking
 * `Action<T>`/Kotlin function types must carry a `@DelegatesTo`
 * `Closure` twin, or Groovy `verb { }` calls SAM-coerce into `Action`
 * and delegate the block to the build script instead of the spec.
 *
 * This module's test classpath sees the whole bundled plugin stack
 * (minecraft/mapping/remap/transform/runs/mod/ide/source/migrate +
 * providers, remappers, transforms), so one scan covers every DSL
 * surface the `xyz.wagyourtail.unimined` frontend ships. Publish and
 * the adapters run the same guard over their own classpaths.
 *
 * New DSL verbs flagged here need either a `Closure` overload or a
 * [xyz.wagyourtail.unimined.api.util.NoGroovyTwin] opt-out when the
 * member is a Kotlin-only wiring seam.
 */
class GroovyTwinParityTest {

    @Test
    fun `every public DSL verb has a Closure twin`() {
        val missing = GroovyTwinParity.missingTwins("xyz.wagyourtail.unimined")
        assertTrue(
            missing.isEmpty(),
            "DSL methods missing @DelegatesTo Closure twins " +
                    "(Groovy SAM-coerces to Action with the script as " +
                    "delegate):\n  " + missing.joinToString("\n  ")
        )
    }

}
