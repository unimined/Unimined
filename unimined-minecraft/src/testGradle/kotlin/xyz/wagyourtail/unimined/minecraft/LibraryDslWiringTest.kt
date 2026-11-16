package xyz.wagyourtail.unimined.minecraft

import groovy.lang.Closure
import org.gradle.api.artifacts.Dependency
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import xyz.wagyourtail.unimined.api.spec.applyLibraryRules
import xyz.wagyourtail.unimined.minecraft.fakes.FakeProvider
import java.nio.file.Path

/**
 * Kotlin-path coverage for the environment's library DSL — the Groovy
 * `Closure` twins are already exercised by [EnvGroovyDslTest]. Each
 * verb is asserted against the *applied* surface: filters evaluate
 * real [Dependency] objects / the populated `minecraftLibraries`
 * bucket, and `replaceLibraryVersion`/`libraryCoordFilter`/
 * `librarySubstitutions` feed [applyLibraryRules] — the same
 * evaluation the providers call when parsing the version JSON.
 */
class LibraryDslWiringTest {

    @TempDir
    lateinit var dir: Path

    private fun project() = ProjectBuilder.builder()
        .withProjectDir(dir.toFile())
        .build()
        .also {
            it.plugins.apply("java")
            it.plugins.apply(GradlePlugin::class.java)
        }

    private fun env(project: org.gradle.api.Project) =
        project.extensions.getByType(MinecraftExtension::class.java).main

    private fun dep(project: org.gradle.api.Project, notation: String) =
        project.dependencies.create(notation)

    @BeforeEach
    fun resetFake() = FakeProvider.reset()

    @Test
    fun `libraryFilter predicate keeps matching deps and drops the rest`() {
        val project = project()
        val env = env(project)

        env.libraryFilter { dep -> dep.group == "com.keep" }

        assertEquals(1, env.libraryFilters.size)
        val filter = env.libraryFilters.single()
        assertTrue(filter(dep(project, "com.keep:lib:1.0")))
        assertFalse(filter(dep(project, "com.drop:lib:1.0")))
    }

    @Test
    fun `libraryFilters gate the provider-declared libraries bucket`() {
        val project = project()
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        ext.version.set("1.0")
        val env = ext.main
        FakeProvider.libraries = listOf(
            dep(project, "com.keep:lib:1.0"),
            dep(project, "com.drop:lib:1.0")
        )
        env.libraryFilter { dep -> dep.group == "com.keep" }

        // `withDependencies` fires on the resolvable (incoming)
        // dependency query — the same trigger resolution uses
        fun coords(conf: String) = project.configurations
            .getByName(conf).incoming.dependencies
            .map { "${it.group}:${it.name}" }

        // the bucket materializes provider libs through the filters,
        // then appends the jetbrains-annotations stub (dev surface)
        assertEquals(listOf("com.keep:lib", "org.jetbrains:annotations"),
            coords(env.minecraftLibrariesConfigurationName))
        // the declared-only view applies the same filter, sans stub
        assertEquals(listOf("com.keep:lib"),
            coords(env.minecraftDeclaredLibrariesConfigurationName))
    }

    @Test
    fun `replaceLibraryVersion lambda overload applies through applyLibraryRules`() {
        val env = env(project())
        env.replaceLibraryVersion(
            group = "com\\.example",
            name = "lwjgl",
            version = { "3.3.3" }
        )

        val rule = env.libraryRules.single()
        assertEquals("3.3.3", rule.version("2.9.1"))
        // same evaluation path the providers run on the version JSON
        assertEquals(
            "com.example:lwjgl:3.3.3",
            applyLibraryRules("com.example:lwjgl:2.9.1",
                env.librarySubstitutions, env.libraryRules,
                env.libraryCoordFilters)
        )
        // non-matching coords pass through untouched
        assertEquals(
            "com.other:lib:1.0",
            applyLibraryRules("com.other:lib:1.0",
                env.librarySubstitutions, env.libraryRules,
                env.libraryCoordFilters)
        )
    }

    @Test
    fun `replaceLibraryVersion matches classifier and can drop`() {
        val env = env(project())

        // classifier regex only consults 4-part coords — the natives
        // entry drops, the bare jar keeps its version
        env.replaceLibraryVersion(
            name = "lwjgl-platform", classifier = "natives-osx"
        ) { null }
        assertNull(
            applyLibraryRules("org.lwjgl:lwjgl-platform:2.9.1:natives-osx",
                env.librarySubstitutions, env.libraryRules,
                env.libraryCoordFilters)
        )
        assertEquals(
            "org.lwjgl:lwjgl-platform:2.9.1:natives-linux",
            applyLibraryRules("org.lwjgl:lwjgl-platform:2.9.1:natives-linux",
                env.librarySubstitutions, env.libraryRules,
                env.libraryCoordFilters)
        )

        // fixed-version overload — a constant replacement, not a fn
        env.replaceLibraryVersion(
            group = "org\\.lwjgl", name = "lwjgl_util",
            version = "3.3.1"
        )
        assertEquals(2, env.libraryRules.size)
        assertEquals("3.3.1", env.libraryRules[1].version("2.9.4"))
        assertEquals(
            "org.lwjgl:lwjgl_util:3.3.1",
            applyLibraryRules("org.lwjgl:lwjgl_util:2.9.4",
                env.librarySubstitutions, env.libraryRules,
                env.libraryCoordFilters)
        )
    }

    @Test
    fun `replaceLibraryVersion closure overload delegates to the rule`() {
        val env = env(project())
        val closure = object : Closure<String?>(this) {
            @Suppress("unused")
            fun doCall(version: String): String? = "9.9-$version"
        }
        env.replaceLibraryVersion("net\\.example", "mod", ".*", closure)

        val rule = env.libraryRules.single()
        assertEquals("9.9-1.0", rule.version("1.0"))
        assertEquals(
            "net.example:mod:9.9-1.0",
            applyLibraryRules("net.example:mod:1.0",
                env.librarySubstitutions, env.libraryRules,
                env.libraryCoordFilters)
        )
    }

    @Test
    fun `spec snapshot carries the contributed library state`() {
        val env = env(project())
        env.version.set("1.0")
        env.librarySubstitutions += "org.lwjgl" to "3.0.0"
        env.replaceLibraryVersion(name = "obsolete") { null }
        env.libraryCoordFilter { c -> if (c.startsWith("com.gone:")) null else c }

        val spec = env.spec()
        assertEquals(listOf("org.lwjgl" to "3.0.0"), spec.librarySubstitutions)
        assertEquals(env.libraryRules.toList(), spec.libraryRules)
        assertEquals(env.libraryCoordFilters.toList(), spec.libraryCoordFilters)

        // and the snapshot applies end-to-end: substitution prefix
        // match, then user rule, then coord filter
        assertEquals(
            "org.lwjgl:lwjgl:3.0.0",
            applyLibraryRules("org.lwjgl:lwjgl:2.9.1",
                spec.librarySubstitutions, spec.libraryRules,
                spec.libraryCoordFilters)
        )
        assertNull(
            applyLibraryRules("com.gone:lib:1.0",
                spec.librarySubstitutions, spec.libraryRules,
                spec.libraryCoordFilters)
        )
        assertNull(
            applyLibraryRules("any:obsolete:1.0",
                spec.librarySubstitutions, spec.libraryRules,
                spec.libraryCoordFilters)
        )
    }

    @Test
    fun `libraryCoordFilter rewrites, drops, and runs last-declared first`() {
        val env = env(project())

        // declared first: rewrites a:b:1 → a:b:2; it never sees that
        // coordinate because the later-declared filter runs first
        env.libraryCoordFilter { c ->
            if (c == "a:b:1") "a:b:2" else c
        }
        env.libraryCoordFilter { c ->
            if (c == "a:b:1") "x:y:3" else c
        }
        env.libraryCoordFilter { c ->
            if (c.startsWith("com.gone:")) null else c
        }

        // applyLibraryRules iterates the list reversed — the
        // last-declared filter decides a:b:1 first
        assertEquals(
            "x:y:3",
            applyLibraryRules("a:b:1",
                emptyList(), emptyList(), env.libraryCoordFilters)
        )
        assertNull(
            applyLibraryRules("com.gone:lib:1.0",
                emptyList(), emptyList(), env.libraryCoordFilters)
        )
        assertEquals(
            "com.keep:lib:1.0",
            applyLibraryRules("com.keep:lib:1.0",
                emptyList(), emptyList(), env.libraryCoordFilters)
        )
    }

    @Test
    fun `libraryCoordFilter closure twin applies the same rewrite`() {
        val env = env(project())
        val closure = object : Closure<String?>(this) {
            @Suppress("unused")
            fun doCall(coord: String): String? =
                if (coord.startsWith("bad:")) null else coord
        }
        env.libraryCoordFilter(closure)

        assertNull(env.libraryCoordFilters.single()("bad:lib:1"))
        assertEquals("ok:lib:1", env.libraryCoordFilters.single()("ok:lib:1"))
    }

    @Test
    fun `default rules drop unsatisfiable natives before user rules`() {
        val env = env(project())
        // no user rules at all — DEFAULT_LIBRARY_RULES still applies:
        // the group+name match drops the artifact outright (the
        // classifier regex only consults 4-part coords)
        assertNull(
            applyLibraryRules("ca.weblite:java-objc-bridge:1.0.0:natives-osx",
                env.librarySubstitutions, env.libraryRules,
                env.libraryCoordFilters)
        )
        assertNull(
            applyLibraryRules("ca.weblite:java-objc-bridge:1.0.0",
                env.librarySubstitutions, env.libraryRules,
                env.libraryCoordFilters)
        )
        // a user substitution still applies to untouched coords
        env.librarySubstitutions += "org.lwjgl" to "3.0.0"
        assertEquals(
            "org.lwjgl:lwjgl:3.0.0",
            applyLibraryRules("org.lwjgl:lwjgl:2.9.1",
                env.librarySubstitutions, env.libraryRules,
                env.libraryCoordFilters)
        )
    }

    @Test
    fun `forceRemap predicate form registers a working filter`() {
        val project = project()
        val env = env(project)

        env.forceRemap { dep -> dep.name == "obf-lib" }

        val filter = env.forceRemapFilters.single()
        assertTrue(filter(dep(project, "com.x:obf-lib:1.0")))
        assertFalse(filter(dep(project, "com.x:plain:1.0")))
    }

    @Test
    fun `forceRemap closure twin registers a working filter`() {
        val project = project()
        val env = env(project)
        val closure = object : Closure<Boolean>(this) {
            @Suppress("unused")
            fun doCall(dep: Dependency): Boolean = dep.group == "com.forced"
        }
        env.forceRemap(closure)

        val filter = env.forceRemapFilters.single()
        assertTrue(filter(dep(project, "com.forced:lib:1.0")))
        assertFalse(filter(dep(project, "com.other:lib:1.0")))
    }

    @Test
    fun `forceRemap notation predicate matches group name and version`() {
        val project = project()
        val env = env(project)

        env.forceRemap("com.example:obf-lib:1.0")
        env.forceRemap("com.example:any-version")

        assertEquals(2, env.forceRemapFilters.size)
        val pinned = env.forceRemapFilters[0]
        assertTrue(pinned(dep(project, "com.example:obf-lib:1.0")))
        assertFalse(pinned(dep(project, "com.example:obf-lib:2.0")))
        assertFalse(pinned(dep(project, "com.other:obf-lib:1.0")))
        val anyVersion = env.forceRemapFilters[1]
        assertTrue(anyVersion(dep(project, "com.example:any-version:9.9")))
        assertFalse(anyVersion(dep(project, "com.example:other:1.0")))
    }

}
