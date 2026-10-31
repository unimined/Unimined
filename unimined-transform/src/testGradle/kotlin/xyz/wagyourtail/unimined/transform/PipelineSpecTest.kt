package xyz.wagyourtail.unimined.transform

import org.gradle.api.Action
import org.gradle.api.Project
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.Assertions.assertEquals
import xyz.wagyourtail.unimined.transform.step.PipelineStepSpec
import xyz.wagyourtail.unimined.transform.step.StepType
import xyz.wagyourtail.unimined.transform.step.StepTypeRegistry
import java.nio.file.Path

class PipelineSpecTest {

    @TempDir
    lateinit var dir: Path

    private val project: Project by lazy {
        ProjectBuilder.builder().withProjectDir(dir.toFile()).build()
    }

    private val registry = StepTypeRegistry()
    private val presets = PipelinePresetRegistry()

    private val pipeline: PipelineSpec by lazy {
        project.objects.newInstance(
            PipelineSpec::class.java, registry, project.objects, presets
        )
    }

    private fun type(id: String) = object : StepType {
        override val id = id
    }

    abstract class FakeStepSpec : PipelineStepSpec

    private fun PipelineSpec.fakeStep(id: String) =
        step(id, FakeStepSpec::class.java) { }

    init {
        registry.register(type("x:a"))
        registry.register(type("x:b"))
        registry.register(type("x:c"))
    }

    @Test
    fun `declare order preserved`() {
        pipeline.fakeStep("x:a")
        pipeline.fakeStep("x:b")
        assertEquals(listOf("x:a", "x:b"),
            pipeline.realize { null }.map { it.type.id })
    }

    @Test
    fun `unregistered step type fails at declaration`() {
        assertThrows<IllegalArgumentException> {
            pipeline.step("x:missing", FakeStepSpec::class.java) { }
        }
    }

    @Test
    fun `select branch expands on probe hit`() {
        pipeline.fakeStep("x:a")
        pipeline.select("era") { sel ->
            sel.branch("userdev") { it.fakeStep("x:b") }
            sel.branch("binpatches") { it.fakeStep("x:c") }
        }
        assertEquals(listOf("x:a", "x:b"),
            pipeline.realize { "userdev" }.map { it.type.id })
        assertEquals(listOf("x:a", "x:c"),
            pipeline.realize { "binpatches" }.map { it.type.id })
    }

    @Test
    fun `select with no matching branch fails at realization`() {
        pipeline.select("era") { sel ->
            sel.branch("userdev") { }
        }
        assertThrows<IllegalStateException> {
            pipeline.realize { "unknown" }
        }
    }

    @Test
    fun `era expands preset steps`() {
        presets.register(object : PipelinePreset {
            override val name = "testEra"
            override fun expand(p: PipelineSpec) {
                p.fakeStep("x:a")
                p.fakeStep("x:c")
            }
        })
        pipeline.era("testEra")
        pipeline.fakeStep("x:b")
        assertEquals(listOf("x:a", "x:c", "x:b"),
            pipeline.realize { null }.map { it.type.id })
    }

    @Test
    fun `unknown era fails at declaration`() {
        assertThrows<IllegalArgumentException> {
            pipeline.era("noSuchEra")
        }
    }

    @Test
    fun `era inside select branch composes`() {
        presets.register(object : PipelinePreset {
            override val name = "testEra"
            override fun expand(p: PipelineSpec) {
                p.fakeStep("x:c")
            }
        })
        pipeline.select("era") { sel ->
            sel.branch("preset") { it.era("testEra") }
        }
        assertEquals(listOf("x:c"),
            pipeline.realize { "preset" }.map { it.type.id })
    }

    @Test
    fun `topological ordering applies across branches`() {
        registry.register(object : StepType {
            override val id = "x:late"
            override val requires = setOf("x:a")
        })
        pipeline.select("era") { sel ->
            sel.branch("e") { p ->
                p.step("x:late", FakeStepSpec::class.java) { }
            }
        }
        pipeline.fakeStep("x:a")
        // x:late requires x:a — after branch expansion, ordering
        // places x:a first despite declaration order
        assertEquals(listOf("x:a", "x:late"),
            pipeline.realize { "e" }.map { it.type.id })
    }

}
