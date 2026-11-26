package xyz.wagyourtail.unimined.remap

import org.gradle.api.Action
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import xyz.wagyourtail.unimined.remap.spi.RemapSurface

/**
 * The two-tier convention contract of `modArtifacts { }` (see
 * [RemapEnvironmentSpec.modArtifactsConventions] /
 * [RemapEnvironmentSpec.modArtifactsCorrectnessConventions]):
 *
 * - **correctness** conventions pre-seed even explicit user
 *   declarations — declaring the step for unrelated reasons must not
 *   drop a boot-critical rewrite — but a deliberate user opt-out of
 *   the flag itself is respected;
 * - **convenience** conventions only shape the auto-appended default;
 *   explicit declarations take full control of them.
 */
class ModArtifactsConventionTest {

    private fun extension(): RemapExtension {
        val project = ProjectBuilder.builder().build()
        project.plugins.apply(GradlePlugin::class.java)
        return project.extensions.getByType(RemapExtension::class.java)
    }

    /** Registers the forge-wiring-shaped fixture conventions: a
     *  correctness pre-seed of the launchwrapper ldc flag plus a
     *  convenience refmap suppression. */
    private fun registerConventions(env: RemapEnvironmentSpec) {
        env.modArtifactsCorrectnessConventions += Action { spec ->
            spec.preseedLaunchwrapperTransformerLdcRemap()
        }
        env.modArtifactsConventions += Action { spec ->
            spec.emitRefmap = false
        }
    }

    private fun declaredSpec(ext: RemapExtension): ModArtifactsStepSpec {
        // the pipeline projection reuses the user's declared instance —
        // correctness pre-seeds mutate it in place at materialization
        val steps = ext.pipelineSteps("main", RemapSurface.JAR).get()
        return steps.filterIsInstance<ModArtifactsStepSpec>().single()
    }

    @Test
    fun `undeclared pipeline auto-appends with both convention tiers`() {
        val ext = extension()
        val env = ext.sourceSet("main")
        env.jar { it.remap("tiny") }
        registerConventions(env)

        val spec = declaredSpec(ext)
        assertTrue(spec.remapLaunchwrapperTransformerLdcs,
            "the undeclared path is unchanged: auto-appended spec gets the correctness pre-seed")
        assertFalse(spec.emitRefmap,
            "the undeclared path is unchanged: auto-appended spec gets convenience conventions")
    }

    @Test
    fun `declared spec still gets the correctness pre-seed`() {
        val ext = extension()
        val env = ext.sourceSet("main")
        env.jar { surface ->
            surface.remap("tiny")
            // declared for unrelated reasons — the lexforge boot-fatal:
            // this must not silently drop the loader-critical ldc pass
            surface.modArtifacts { spec ->
                spec.enableMixinExtra()
            }
        }
        registerConventions(env)

        val spec = declaredSpec(ext)
        assertTrue(spec.remapLaunchwrapperTransformerLdcs,
            "correctness flags pre-seed explicit modArtifacts declarations")
        assertTrue(spec.emitRefmap,
            "convenience conventions never touch an explicit declaration — " +
            "the user took full control of those knobs")
        assertTrue(spec.forceMixinTables.contains("mixinextras"),
            "the user's own configuration survives pre-seeding")
    }

    @Test
    fun `explicit disable of the correctness flag is respected`() {
        val ext = extension()
        val env = ext.sourceSet("main")
        env.jar { surface ->
            surface.remap("tiny")
            surface.modArtifacts { spec ->
                spec.disableLaunchwrapperTransformerLdcRemap()
            }
        }
        registerConventions(env)

        val spec = declaredSpec(ext)
        assertFalse(spec.remapLaunchwrapperTransformerLdcs,
            "a deliberate user opt-out must survive loader correctness pre-seeds")
    }

    @Test
    fun `explicit enable of the correctness flag is convergent`() {
        val ext = extension()
        val env = ext.sourceSet("main")
        env.jar { surface ->
            surface.remap("tiny")
            surface.modArtifacts { spec ->
                spec.enableLaunchwrapperTransformerLdcRemap()
            }
        }
        registerConventions(env)

        assertTrue(declaredSpec(ext).remapLaunchwrapperTransformerLdcs)
    }

    @Test
    fun `correctness pre-seed applies through the dependency projection too`() {
        val ext = extension()
        val env = ext.sourceSet("main")
        env.jar { surface ->
            surface.remap("tiny")
            surface.modArtifacts { spec ->
                spec.enableMixinExtra()
            }
        }
        registerConventions(env)

        // the unimixins-on-lexforge7 path: mod deps remap through
        // dependencyPipelineSteps — the declared spec instance is
        // shared, so the in-place pre-seed covers every projection
        val spec = ext.dependencyPipelineSteps("main", RemapSurface.JAR)
            .get().filterIsInstance<ModArtifactsStepSpec>().single()
        assertTrue(spec.remapLaunchwrapperTransformerLdcs,
            "dependency mod artifacts get the boot-critical ldc pass")
    }

    @Test
    fun `re-materializing the pipeline is stable`() {
        val ext = extension()
        val env = ext.sourceSet("main")
        env.jar { surface ->
            surface.remap("tiny")
            surface.modArtifacts { spec ->
                spec.disableLaunchwrapperTransformerLdcRemap()
            }
        }
        registerConventions(env)

        repeat(3) {
            assertFalse(declaredSpec(ext).remapLaunchwrapperTransformerLdcs,
                "iteration $it: repeated materialization must not wear down the user opt-out")
        }
    }

}
