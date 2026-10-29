package xyz.wagyourtail.unimined.mapping

import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
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
            mappingFile.toFile(),
            (sources[0] as MappingsSpecImpl.Source.File).files.singleFile
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

}
