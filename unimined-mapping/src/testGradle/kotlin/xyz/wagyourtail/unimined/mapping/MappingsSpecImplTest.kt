package xyz.wagyourtail.unimined.mapping

import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class MappingsSpecImplTest {

    @TempDir
    lateinit var dir: Path

    private fun objects() = ProjectBuilder.builder()
        .withProjectDir(dir.toFile())
        .build()
        .objects

    private fun project() = ProjectBuilder.builder()
        .withProjectDir(dir.toFile())
        .build()

    @Test
    fun `file and maven sources preserve declaration order lazily`() {
        val spec = MappingsSpecImpl(objects())
        val mappingFile = dir.resolve("mappings.tiny")

        spec.file(mappingFile)
        spec.maven("net.fabricmc:intermediary:1.21")
        spec.official()

        val sources = spec.effectiveSources()
        assertEquals(3, sources.size)
        assertEquals(
            mappingFile,
            (sources[0] as MappingsSpecImpl.Source.File).files().single()
        )
        assertEquals(
            "net.fabricmc:intermediary:1.21",
            (sources[1] as MappingsSpecImpl.Source.Maven).notation
        )
        assertEquals(
            "official",
            (sources[2] as MappingsSpecImpl.Source.Builtin).nameProvider.get()
        )
    }

    @Test
    fun `direct sources override inherited sources`() {
        val inherited = MappingsSpecImpl(objects()).apply {
            maven("a:b:1")
        }
        val target = MappingsSpecImpl(objects())
        target.inheritSources { inherited.effectiveSources(it) }
        target.maven("a:b:2")

        assertEquals(
            "a:b:2",
            (target.effectiveSources().single() as MappingsSpecImpl.Source.Maven).notation
        )
    }

    @Test
    fun `cyclic mapping inheritance fails clearly`() {
        val first = MappingsSpecImpl(objects())
        val second = MappingsSpecImpl(objects())
        first.inheritSources { second.effectiveSources(it) }
        second.inheritSources { first.effectiveSources(it) }

        assertThrows(Exception::class.java) { first.effectiveSources() }
    }

    /** D2 — `customIntermediaries` is read inside fabric-family
     *  contribution providers, so a provider-set value must flow
     *  through without resolving at declaration time. */
    @Test
    fun `customIntermediaries accepts a provider resolved on read`() {
        val project = project()
        val spec = MappingsSpecImpl(project.objects)
        var evaluations = 0
        spec.setCustomIntermediaries(
            project.provider { evaluations++; true })
        assertEquals(0, evaluations,
            "customIntermediaries provider must not resolve at set time")
        assertTrue(spec.customIntermediaries.get())
        assertEquals(1, evaluations)

        // the direct-value setter keeps the non-lazy form
        spec.setCustomIntermediaries(false)
        assertEquals(false, spec.customIntermediaries.get())
    }

    /** D2 — `hierarchyNameFill` is read inside mapping-data
     *  materialization (`resolvedMappingData`), so a provider-set
     *  value stays unevaluated until a consumer resolves it; the
     *  unset spec keeps the auto-enable tri-state (absent, not
     *  `false`). */
    @Test
    fun `hierarchyNameFill accepts a provider resolved on read`() {
        val project = project()
        val spec = MappingsSpecImpl(project.objects)
        var evaluations = 0
        spec.setHierarchyNameFill(
            project.provider { evaluations++; false })
        assertEquals(0, evaluations,
            "hierarchyNameFill provider must not resolve at set time")
        assertEquals(false, spec.hierarchyNameFill.orNull)
        assertEquals(1, evaluations)

        // unset keeps the auto-enable tri-state — absent, not false
        assertEquals(null,
            MappingsSpecImpl(project.objects).hierarchyNameFill.orNull)
    }

}
