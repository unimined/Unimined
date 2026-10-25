package xyz.wagyourtail.unimined.remapper.source

import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.gradle.api.file.FileSystemLocation
import xyz.wagyourtail.unimined.api.spec.RemapSpec
import xyz.wagyourtail.unimined.remap.RemapStepRegistry
import xyz.wagyourtail.unimined.remap.spi.RemapSurface

class SourceRemapStepTest {

    private val spec = object : RemapSpec {
        override val sourceNamespace = "official"
        override val targetNamespace = "named"
        override val classpath: List<FileSystemLocation> = emptyList()
    }

    private val objects = ProjectBuilder.builder().build().objects

    @Test
    fun `service loader discovers the impl`() {
        val stepSpec = objects.newInstance(SourceRemapStepSpec::class.java)
        val type = RemapStepRegistry().resolve(stepSpec, RemapSurface.SOURCES, spec)
        assertEquals("source", type.name)
        assertEquals("remap", type.stepName)
    }

    @Test
    fun `only the sources surface is supported`() {
        val stepSpec = objects.newInstance(SourceRemapStepSpec::class.java)
        assertThrows<IllegalArgumentException> {
            RemapStepRegistry().resolve(stepSpec, RemapSurface.JAR, spec)
        }
    }

}
