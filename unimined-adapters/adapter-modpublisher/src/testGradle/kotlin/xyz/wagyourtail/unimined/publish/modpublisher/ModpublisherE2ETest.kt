package xyz.wagyourtail.unimined.publish.modpublisher

import com.hypherionmc.modpublisher.plugin.ModPublisherGradleExtension
import io.github.pacifistmc.forgix.plugin.ForgixGradlePlugin
import org.gradle.api.internal.project.ProjectInternal
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import java.io.File

/**
 * No adapter DSL — inferred values land on `publisher { }` as
 * conventions (a user-set value always wins), per plan §11.
 */
class ModpublisherE2ETest {

    private fun project(withForgix: Boolean = false): ProjectInternal {
        val p = ProjectBuilder.builder().build() as ProjectInternal
        p.pluginManager.apply("java")
        p.pluginManager.apply(
            xyz.wagyourtail.unimined.minecraft.GradlePlugin::class.java)
        if (withForgix) {
            p.pluginManager.apply(ForgixGradlePlugin::class.java)
            p.plugins.apply(
                xyz.wagyourtail.unimined.loadermerge.GradlePlugin::class.java)
        }
        p.pluginManager.apply("com.hypherionmc.modutils.modpublisher")
        p.plugins.apply(GradlePlugin::class.java)
        return p
    }

    private fun publisher(p: ProjectInternal) =
        p.extensions.getByName("publisher") as ModPublisherGradleExtension

    @Test
    fun `env metadata conventions into publisher`() {
        val p = project()
        p.evaluate()
        val pub = publisher(p)
        // `main` env binds → its env id is the per-loader key; no
        // loaderSpec/version set in the fixture, so metadata lists
        // stay empty rather than guessing
        assertTrue(pub.loaders.get().isEmpty())
        assertTrue(pub.gameVersions.get().isEmpty())
    }

    @Test
    fun `merged artifact suppresses per-loader jars`() {
        val p = project(withForgix = true)
        p.evaluate()
        val pub = publisher(p)

        // mergedElements exists (forgix adapter applied) → the merged
        // jar is the artifact and nothing leaks into additionalFiles
        val merged = p.configurations.getByName("mergedElements")
            .outgoing.artifacts.first().file
        assertEquals(merged, pub.artifact.get())
        assertTrue(
            pub.additionalFiles.get().isEmpty(),
            "per-loader jars must not leak into additionalFiles: " +
                    pub.additionalFiles.get()
        )
    }

    @Test
    fun `user-set artifact wins over the convention`() {
        val p = project(withForgix = true)
        val custom = p.file("build/libs/custom.jar")
        publisher(p).artifact.set(custom)
        p.evaluate()
        assertEquals(custom, publisher(p).artifact.get())
    }

    @Test
    fun `no forgix adapter means no merged artifact convention`() {
        val p = project()
        p.evaluate()
        // mergedArtifact absent → artifact convention falls to the
        // bound env's prod jar path (`main`'s jar task output)
        assertNull(p.configurations.findByName("mergedElements"))
        assertTrue(publisher(p).artifact.isPresent,
            "per-loader jar should still convention the artifact")
    }

}
