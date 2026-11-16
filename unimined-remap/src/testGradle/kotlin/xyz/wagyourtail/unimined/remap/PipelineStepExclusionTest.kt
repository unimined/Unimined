package xyz.wagyourtail.unimined.remap

import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import xyz.wagyourtail.unimined.remap.spi.EmitArtifactStepSpec
import xyz.wagyourtail.unimined.remap.spi.RemapSurface
import xyz.wagyourtail.unimined.remap.spi.StepScope
import xyz.wagyourtail.unimined.remap.spi.StepSpec
import xyz.wagyourtail.unimined.remap.spi.StripNestedJarsStepSpec

/**
 * The three pipeline projections over one environment's contributions:
 *
 * - [RemapExtension.pipelineSteps] — the environment's own artifact,
 *   env-scoped contributions included, plus the prod-emit tap.
 * - [RemapExtension.dependencyPipelineSteps] — remapped dep views:
 *   `ENVIRONMENT_ARTIFACT`-scoped contributions excluded (loader patch
 *   steps only apply to the env's own jar), `StripNestedJarsStepSpec`
 *   appended on JAR.
 * - the `artifact {}` standalone pipeline — environment-only AND
 *   mod-artifact steps excluded.
 */
class PipelineStepExclusionTest {

    private class SharedStepSpec : StepSpec
    private class EnvOnlyStepSpec : StepSpec

    private fun extension(): RemapExtension {
        val project = ProjectBuilder.builder().build()
        project.plugins.apply(GradlePlugin::class.java)
        return project.extensions.getByType(RemapExtension::class.java)
    }

    private fun configuredEnv(ext: RemapExtension): RemapEnvironmentSpec {
        val env = ext.sourceSet("main")
        env.jar { it.remap("tiny") }
        env.contributePreStep(
            RemapSurface.JAR, EnvOnlyStepSpec(),
            StepScope.ENVIRONMENT_ARTIFACT
        )
        env.contributePreStep(
            RemapSurface.JAR, SharedStepSpec(), StepScope.ALL_ARTIFACTS
        )
        env.contributeStep(
            RemapSurface.JAR, EnvOnlyStepSpec(),
            StepScope.ENVIRONMENT_ARTIFACT
        )
        env.contributeStep(
            RemapSurface.JAR, SharedStepSpec(), StepScope.ALL_ARTIFACTS
        )
        return env
    }

    @Test
    fun `dependencyPipelineSteps drops env-only steps and strips nested jars`() {
        val ext = extension()
        configuredEnv(ext)

        val full = ext.pipelineSteps("main", RemapSurface.JAR).get()
        // the environment pipeline: pre contributions, prod-emit tap,
        // declared remap, mod-artifact pass, post contributions
        assertEquals(
            2, full.count { it is EnvOnlyStepSpec },
            "env pipeline carries both env-scoped contributions"
        )
        assertTrue(full.any { it is EmitArtifactStepSpec },
            "env pipeline emits the prod-jar tap")
        assertTrue(full.any { it is ModArtifactsStepSpec })

        val dep = ext.dependencyPipelineSteps("main", RemapSurface.JAR).get()
        assertTrue(dep.none { it is EnvOnlyStepSpec },
            "dep views never see env-artifact-scoped steps")
        // shared contributions still apply to dep remaps
        assertEquals(2, dep.count { it is SharedStepSpec })
        assertTrue(dep.any { it is RemapStepSpec })
        assertTrue(dep.any { it is ModArtifactsStepSpec },
            "dep jars still get mod-metadata fixes")
        assertTrue(dep.none { it is EmitArtifactStepSpec },
            "no prod-emit tap on dep views")
        assertEquals(1, dep.count { it is StripNestedJarsStepSpec },
            "dep jars drop JiJ declarations post-remap")
    }

    @Test
    fun `dependencyPipelineSteps strips nested jars on JAR only`() {
        val ext = extension()
        ext.sourceSet("main").jar { it.remap("tiny") }

        assertTrue(ext.dependencyPipelineSteps("main", RemapSurface.JAR)
            .get().any { it is StripNestedJarsStepSpec })
        // a sources pipeline resolves its own implicit remap core but
        // gains no dep-jar JiJ stripping
        assertTrue(ext.dependencyPipelineSteps("main", RemapSurface.SOURCES)
            .get().none { it is StripNestedJarsStepSpec })
    }

    @Test
    fun `standalone artifact steps exclude env-only and mod-artifact steps`() {
        val ext = extension()
        configuredEnv(ext)

        val task = ext.artifact { spec ->
            spec.input.fileValue(java.io.File("in.jar"))
            spec.mappings.fileValue(java.io.File("mappings.tiny"))
            spec.from.set("official")
            spec.to.set("named")
        }.get()

        val steps = task.stepSpecs.get()
        assertTrue(steps.any { it is RemapStepSpec })
        assertTrue(steps.any { it is SharedStepSpec })
        assertTrue(steps.none { it is EnvOnlyStepSpec })
        assertTrue(steps.none { it is ModArtifactsStepSpec },
            "mod-artifact passes only run for environment jars")
        assertTrue(steps.none { it is EmitArtifactStepSpec })
        assertTrue(steps.none { it is StripNestedJarsStepSpec },
            "the standalone pipeline is not a dep view — no JiJ strip")
    }

}
