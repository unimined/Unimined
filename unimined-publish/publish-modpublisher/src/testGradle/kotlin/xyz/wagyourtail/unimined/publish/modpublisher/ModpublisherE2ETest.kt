package xyz.wagyourtail.unimined.publish.modpublisher

import com.hypherionmc.modpublisher.plugin.ModPublisherGradleExtension
import org.gradle.api.internal.project.ProjectInternal
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import java.io.File

/**
 * Dry-run e2e (plan §11): with ModPublisher applied, our config +
 * [ArtifactSelection] land on `publisher{}`. debug.set(true) — no
 * upload is ever attempted.
 */
class ModpublisherE2ETest {

    private fun evaluated(): Pair<ProjectInternal, ModpublisherExtension> {
        val p = ProjectBuilder.builder().build() as ProjectInternal
        p.pluginManager.apply("com.hypherionmc.modutils.modpublisher")
        p.plugins.apply(GradlePlugin::class.java)
        val ext = p.extensions.getByType(ModpublisherExtension::class.java)
        return p to ext
    }

    @Test
    fun `merged artifact and platform config land on publisher ext`() {
        val (p, ext) = evaluated()
        val merged = p.file("build/libs/merged.jar")
        ext.mergedArtifact.set(p.layout.file(p.provider { merged }))
        ext.perLoaderArtifacts.put("forge", p.layout.file(p.provider { p.file("prod.jar") }))
        ext.curseID.set("1234")
        ext.version.set("1.0.0")
        ext.debug.set(true)
        (p as ProjectInternal).evaluate()

        val pub = p.extensions.getByName("publisher") as ModPublisherGradleExtension
        assertEquals(merged, pub.artifact.get())
        assertEquals("1234", pub.curseID.get())
        assertEquals("1.0.0", pub.version.get())
        assertTrue(pub.debug.get())
    }

    @Test
    fun `per-loader prod lands when no merged artifact`() {
        val (p, ext) = evaluated()
        val prod = p.file("prod-fabric.jar")
        ext.perLoaderArtifacts.put("fabric", p.layout.file(p.provider { prod }))
        (p as ProjectInternal).evaluate()

        val pub = p.extensions.getByName("publisher") as ModPublisherGradleExtension
        assertEquals(prod, pub.artifact.get())
    }

}
