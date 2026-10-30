package xyz.wagyourtail.unimined.remapper.source

import java.nio.file.Path
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import xyz.wagyourtail.unimined.api.spec.RemapSpec
import xyz.wagyourtail.unimined.remap.RemapStepRegistry
import xyz.wagyourtail.unimined.remap.spi.RemapSurface

class SourceRemapStepTest {

    private val spec = object : RemapSpec {
        override val sourceNamespace = "official"
        override val targetNamespace = "named"
        override val classpath: List<Path> = emptyList()
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
        // resolved impl doesn't support the surface — documented
        // registry contract throws IllegalArgumentException
        assertThrows<IllegalArgumentException> {
            RemapStepRegistry().resolve(stepSpec, RemapSurface.JAR, spec)
        }
    }

}
