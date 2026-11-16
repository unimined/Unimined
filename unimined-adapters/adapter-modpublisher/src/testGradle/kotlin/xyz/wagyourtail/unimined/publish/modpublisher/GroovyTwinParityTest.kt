package xyz.wagyourtail.unimined.publish.modpublisher

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import xyz.wagyourtail.unimined.api.testing.GroovyTwinParity

/**
 * Groovy-DSL parity regression guard — every public DSL method taking
 * `Action<T>`/Kotlin function types must carry a `@DelegatesTo`
 * `Closure` twin, or Groovy `verb { }` calls SAM-coerce into `Action`
 * and delegate the block to the build script instead of the spec.
 *
 * Scans this plugin's own classes plus every repo jar on the
 * classpath. New DSL verbs flagged here need either a `Closure`
 * overload or a [xyz.wagyourtail.unimined.api.util.NoGroovyTwin]
 * opt-out when the member is a Kotlin-only wiring seam.
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
