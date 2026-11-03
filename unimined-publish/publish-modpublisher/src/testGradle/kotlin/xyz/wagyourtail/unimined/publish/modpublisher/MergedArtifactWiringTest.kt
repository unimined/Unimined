package xyz.wagyourtail.unimined.publish.modpublisher

import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import xyz.wagyourtail.unimined.loadermerge.LoaderMergeExtension

/**
 * Plan §11: when loader-merge provides a merged artifact it is the
 * default upload artifact (`mergedArtifact` convention).
 */
class MergedArtifactWiringTest {

    @Test
    fun `mergedElements artifact becomes mergedArtifact convention`() {
        val project = ProjectBuilder.builder().build()
        project.plugins.apply(
            xyz.wagyourtail.unimined.loadermerge.GradlePlugin::class.java
        )
        project.plugins.apply(GradlePlugin::class.java)

        val ext = project.extensions.getByType(ModpublisherExtension::class.java)
        val merged = ext.mergedArtifact.orNull
        assertTrue(merged != null, "mergedArtifact convention must resolve")

        val expected = project.configurations
            .getByName("mergedElements").outgoing.artifacts.first().file
        assertEquals(expected, merged!!.asFile)
    }

    @Test
    fun `explicit mergedArtifact overrides the convention`() {
        val project = ProjectBuilder.builder().build()
        project.plugins.apply(
            xyz.wagyourtail.unimined.loadermerge.GradlePlugin::class.java
        )
        project.plugins.apply(GradlePlugin::class.java)

        val ext = project.extensions.getByType(ModpublisherExtension::class.java)
        val override = project.layout.buildDirectory.file("libs/custom.jar")
        ext.mergedArtifact.set(override)
        assertEquals(override.get().asFile, ext.mergedArtifact.get().asFile)
    }

}
