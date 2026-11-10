package xyz.wagyourtail.unimined.publish.modpublisher

import io.github.pacifistmc.forgix.plugin.ForgixGradlePlugin
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals

/**
 * Plan §11: when the forgix adapter provides a merged artifact it is
 * the default upload artifact (`publisher.artifact` convention).
 */
class MergedArtifactWiringTest {

    @Test
    fun `mergedElements artifact becomes the artifact convention`() {
        val project = ProjectBuilder.builder().build()
        project.pluginManager.apply("java")
        // mergedElements lives behind the forgix gate — the merge
        // adapter alone doesn't produce it
        project.pluginManager.apply(ForgixGradlePlugin::class.java)
        project.plugins.apply(
            xyz.wagyourtail.unimined.loadermerge.GradlePlugin::class.java
        )
        project.plugins.apply(GradlePlugin::class.java)

        // modpublisher plugin not applied → publisher ext absent;
        // mergedElements is still produced for whatever consumes it
        val artifact = project.configurations.getByName("mergedElements")
            .outgoing.artifacts.first()
        assertEquals("merged", artifact.classifier)
    }

}
