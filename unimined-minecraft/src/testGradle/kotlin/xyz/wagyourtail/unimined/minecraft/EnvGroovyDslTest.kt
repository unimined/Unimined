package xyz.wagyourtail.unimined.minecraft

import groovy.lang.GroovyShell
import groovy.lang.MissingMethodException
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * Direct-invocation coverage for the env's `Closure` twins — the
 * library-filter/rewrite verbs take Kotlin function types, whose
 * Groovy overloads must dispatch to the `Closure` parameter and wrap
 * `closure.call(...)` (not delegate to the spec — these are
 * predicates, not configuration blocks).
 */
class EnvGroovyDslTest {

    @TempDir
    lateinit var dir: Path

    private fun project() = ProjectBuilder.builder()
        .withProjectDir(dir.toFile())
        .build()
        .also {
            // registers JavaToolchainService — real consumers apply java
            // themselves; the plugin does not
            it.plugins.apply("java")
            it.plugins.apply(GradlePlugin::class.java)
        }

    @Test
    fun `libraryFilter closure twin registers a working keep-filter`() {
        val project = project()
        val env = project.extensions
            .getByType(MinecraftExtension::class.java).main

        val shell = GroovyShell()
        shell.setVariable("env", env)
        shell.evaluate("env.libraryFilter { dep -> dep.group == 'com.example' }")

        val keep = project.dependencies.create("com.example:lib:1.0")
        val drop = project.dependencies.create("org.other:lib:1.0")
        assertEquals(1, env.libraryFilters.size)
        assertTrue(env.libraryFilters.single()(keep))
        assertFalse(env.libraryFilters.single()(drop))
    }

    @Test
    fun `replaceLibraryVersion and libraryCoordFilter closure twins`() {
        val env = project().extensions
            .getByType(MinecraftExtension::class.java).main

        val shell = GroovyShell()
        shell.setVariable("env", env)
        shell.evaluate("env.replaceLibraryVersion('.*', 'lwjgl', '.*') { '3.3.3' }")
        shell.evaluate(
            "env.libraryCoordFilter { coord ->" +
                    " coord.startsWith('bad') ? null : coord }"
        )

        assertEquals("3.3.3", env.libraryRules.single().version("3.1.1"))
        assertNull(env.libraryCoordFilters.single()("bad:lib:1"))
        assertEquals("ok:lib:1", env.libraryCoordFilters.single()("ok:lib:1"))
    }

    private fun messages(e: Throwable): String =
        generateSequence(e) { it.cause }
            .mapNotNull { it.message }.joinToString(" | ")

    /** 1.x `fabric { }` patcher blocks removed in 2.0 must surface the
     *  `fabric("<version>")` migration note through Groovy dispatch —
     *  not a bare missing-method error. */
    @Test
    fun `removed loader patcher verb warns with migration note`() {
        val env = project().extensions
            .getByType(MinecraftExtension::class.java).main
        val shell = GroovyShell()
        shell.setVariable("env", env)
        val e = assertThrows(Exception::class.java) {
            shell.evaluate("env.fabric { }")
        }
        val msg = messages(e)
        assertTrue(msg.contains("fabric") && msg.contains("loader"),
            "expected the fabric(\"<version>\") migration note, got: $msg")
    }

    /** Named loader verbs declare through the same `loader(name, v)`
     *  machinery — `env.fabric '0.16.0'` == `env.loader 'fabric',
     *  '0.16.0'`, including merged multi-loader ordering. */
    @Test
    fun `named loader verbs declare loaders like loader()`() {
        val env = project().extensions
            .getByType(MinecraftExtension::class.java).main
        val shell = GroovyShell()
        shell.setVariable("env", env)
        shell.evaluate("env.fabric '0.16.0'")
        assertEquals("fabric", env.loaderSpec.get().name)
        assertEquals("0.16.0", env.loaderSpec.get().version)
        // a second named declaration joins as compile-only secondary
        shell.evaluate("env.forge '47.2.0'")
        assertEquals("forge", env.loaderSpecs[1].name)
        // 1.x spellings resolve to the canonical descriptor id
        shell.evaluate("env.neoForge '21.0.0'")
        assertEquals("neoforge", env.loaderSpecs[2].name)
    }

    /** `minecraft { fabric '0.16.0' }` — the extension facade on
     *  `main` dispatches the same declaration. */
    @Test
    fun `extension facade declares on main`() {
        val unimined = project().extensions
            .getByType(MinecraftExtension::class.java)
        val shell = GroovyShell()
        shell.setVariable("unimined", unimined)
        shell.evaluate("unimined.fabric '0.16.0'")
        assertEquals("fabric", unimined.main.loaderSpec.get().name)
    }

    /** Env verbs contributed by unapplied plugins name the plugin —
     *  `env.customSearge()` without `transform.jarmod` applied.
     *  (`jarmod`/`fg1` are member declaration verbs now, so they no
     *  longer ride the plugin-verb diagnostic.) */
    @Test
    fun `plugin-contributed verb names its plugin`() {
        val env = project().extensions
            .getByType(MinecraftExtension::class.java).main
        val shell = GroovyShell()
        shell.setVariable("env", env)
        val e = assertThrows(Exception::class.java) {
            shell.evaluate("env.customSearge()")
        }
        val msg = messages(e)
        assertTrue(msg.contains("transform.jarmod"), msg)
    }

    /** `env.version "1.2.3"` — the call-form write is a removed shape;
     *  the property assignment `env.version = '1.2.3'` works. */
    @Test
    fun `property call-form warns, property assignment works`() {
        val env = project().extensions
            .getByType(MinecraftExtension::class.java).main
        val shell = GroovyShell()
        shell.setVariable("env", env)
        val e = assertThrows(Exception::class.java) {
            shell.evaluate("env.version '1.2.3'")
        }
        assertTrue(messages(e).contains("Property"), messages(e))
        shell.evaluate("env.version = '1.2.3'")
        assertEquals("1.2.3", env.version.get())
    }

    /** Modern member verbs and extension-object methods still
     *  dispatch — only truly unknown calls raise missing-method. */
    @Test
    fun `modern verbs work and unknown calls raise missing-method`() {
        val env = project().extensions
            .getByType(MinecraftExtension::class.java).main
        val shell = GroovyShell()
        shell.setVariable("env", env)
        // member verb — no methodMissing involvement
        shell.evaluate("env.disableLvtPatch()")
        assertFalse(env.patchLvts.get())
        // a registered extension object's methods dispatch by name —
        // the path `env.fg1(v)` rides when transform-jarmod applies
        val stub = StubEnvExtension()
        (env as org.gradle.api.plugins.ExtensionAware)
            .extensions.add("stub", stub)
        shell.evaluate("env.stubVerb('hit')")
        assertEquals(listOf("hit"), stub.seen)
        // truly unknown — the missing-method path
        assertThrows(Exception::class.java) {
            shell.evaluate("env.definitelyNotAVerb(1)")
        }
        assertThrows(MissingMethodException::class.java) {
            env.methodMissing("definitelyNotAVerb", arrayOf<Any>(1))
        }
    }

    /** Stand-in for plugin env extensions (`jarmod` + friends) —
     *  Groovy reaches [stubVerb] through Gradle's extension-aware
     *  dispatch or the same-named-method branch of methodMissing. */
    class StubEnvExtension {
        val seen = mutableListOf<String>()
        fun stubVerb(v: String) { seen += v }
    }

}
