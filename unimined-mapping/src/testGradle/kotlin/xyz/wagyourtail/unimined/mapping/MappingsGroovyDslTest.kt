package xyz.wagyourtail.unimined.mapping

import groovy.lang.GroovyShell
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * Direct-invocation coverage for [MappingsSpecImpl]'s `Closure`
 * twins — `stubs(list) { }` delegates its block to `StubsSpec` so
 * the unqualified `c`/`f`/`m` row verbs resolve against the spec
 * rather than the calling script.
 */
class MappingsGroovyDslTest {

    @TempDir
    lateinit var dir: Path

    private fun objects() = ProjectBuilder.builder()
        .withProjectDir(dir.toFile())
        .build()
        .objects

    @Test
    fun `stubs closure twin delegates to StubsSpec`() {
        val spec = MappingsSpecImpl(objects())
        val shell = GroovyShell()
        shell.setVariable("spec", spec)
        shell.evaluate(
            """
            spec.stubs(['official', 'named']) {
                c('a/b/C')
                c(['i/j/K']) {
                    f('x;I', 'y;I')
                    m('r;()V', 's')
                }
                clazz(['d/e/F']) {
                    field('g;I')
                }
            }
            """.trimIndent()
        )

        assertTrue(spec.hasStubs())
        val stubs = spec.sources
            .filterIsInstance<MappingsSpecImpl.Source.Stubs>().single()
        val out = String(stubs.spec.serialize())
        // `c`/`f`/`m` 1.x verbs — including the no-block bare-vararg
        // arities Kotlin's `= {}` defaults hide from Groovy (member
        // rows need `;desc` — the tiny-v2 writer drops desc-less
        // members)
        for (want in listOf("a/b/C", "i/j/K", "x", "y", "r", "s", "()V", "I"))
            assertTrue(out.contains(want), out)
        // nested `clazz`/`field` verbs ride the GroovyMappingTreeDsl
        // scope — Kotlin Function1 block params alone can't dispatch
        for (want in listOf("d/e/F", "g"))
            assertTrue(out.contains(want), out)
    }

}
