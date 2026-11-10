package xyz.wagyourtail.unimined.publish

import org.gradle.api.GradleException
import org.gradle.api.internal.project.ProjectInternal
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import xyz.wagyourtail.unimined.publish.internal.MavenPublicationWiring

/**
 * Plan §11 acceptance: one Maven publication per environment with
 * distinct coordinates — `main` defaults to the project name, other
 * environments to `<project>-<env>`; explicit `artifactId` wins;
 * identical effective coordinates fail the build; `components.java`
 * stays opt-in; `mavenPublications = false` creates nothing.
 */
class MavenPublicationTest {

    private fun project(): ProjectInternal {
        val p = ProjectBuilder.builder().build() as ProjectInternal
        p.pluginManager.apply("java")
        p.pluginManager.apply(
            xyz.wagyourtail.unimined.minecraft.GradlePlugin::class.java)
        p.plugins.apply(GradlePlugin::class.java)
        return p
    }

    private fun publications(p: ProjectInternal) =
        p.extensions.getByType(PublishingExtension::class.java)
            .publications.withType(MavenPublication::class.java)

    private fun publication(p: ProjectInternal, name: String) =
        publications(p).findByName(name)

    @Test
    fun `main env publication defaults to project name`() {
        val p = project()
        p.evaluate()
        val pub = publication(p, "uniminedMain")
        assertNotNull(pub, "expected a uniminedMain publication")
        assertEquals(p.name, pub!!.artifactId)
    }

    @Test
    fun `non-main env defaults to project-env artifactId`() {
        val p = project()
        val minecraft = p.extensions.getByType(
            xyz.wagyourtail.unimined.minecraft.MinecraftExtension::class.java)
        minecraft.sourceSet("fabric") {}
        p.evaluate()
        val pub = publication(p, "uniminedFabric")
        assertNotNull(pub, "expected a uniminedFabric publication")
        assertEquals("${p.name}-fabric", pub!!.artifactId)
    }

    @Test
    fun `explicit artifactId wins over the default`() {
        val p = project()
        val ext = p.extensions.getByType(PublishExtension::class.java)
        ext.environment("fabric") { it.artifactId = "custom-mod" }
        val minecraft = p.extensions.getByType(
            xyz.wagyourtail.unimined.minecraft.MinecraftExtension::class.java)
        minecraft.sourceSet("fabric") {}
        p.evaluate()
        assertEquals("custom-mod", publication(p, "uniminedFabric")!!.artifactId)
    }

    @Test
    fun `identical coordinates across envs fail`() {
        val p = project()
        val ext = p.extensions.getByType(PublishExtension::class.java)
        ext.environment("main") { it.artifactId = "same" }
        ext.environment("fabric") { it.artifactId = "same" }
        val minecraft = p.extensions.getByType(
            xyz.wagyourtail.unimined.minecraft.MinecraftExtension::class.java)
        minecraft.sourceSet("fabric") {}
        p.evaluate()
        val ex = assertThrows(GradleException::class.java) {
            MavenPublicationWiring.finish(p, ext)
        }
        assertTrue(ex.message!!.contains("artifactId"),
            "error should point at the artifactId setting: ${ex.message}")
    }

    @Test
    fun `java publication is opt-in`() {
        val p = project()
        p.evaluate()
        assertNull(publication(p, "java"),
            "components.java must not publish without javaPublication()")

        // second project with the opt-in — the publication is created
        // during evaluate so user afterEvaluate customization resolves
        val p2 = project()
        p2.extensions.getByType(PublishExtension::class.java).javaPublication()
        p2.evaluate()
        val java = publication(p2, "java")
        assertNotNull(java, "javaPublication() should create the java publication")
        // uniminedMain already claims the project name — the plain java
        // component defaults to the -java suffix like non-main envs
        assertEquals("${p2.name}-java", java!!.artifactId)
        // and the coordinate check must not flag it against uniminedMain
        MavenPublicationWiring.finish(
            p2, p2.extensions.getByType(PublishExtension::class.java))
    }

    @Test
    fun `mavenPublications false creates no publications`() {
        val p = project()
        val ext = p.extensions.getByType(PublishExtension::class.java)
        ext.mavenPublications.set(false)
        p.evaluate()
        assertTrue(
            publications(p).none { it.name.startsWith("unimined") },
            "mavenPublications=false must skip unimined publications: " +
                    publications(p).map { it.name }
        )
    }

}
