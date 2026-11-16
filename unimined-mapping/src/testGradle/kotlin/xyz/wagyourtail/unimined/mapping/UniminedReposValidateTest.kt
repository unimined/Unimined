package xyz.wagyourtail.unimined.mapping

import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Test

class UniminedReposValidateTest {

    @Test
    fun `ensure records the repo on the handler`() {
        val project = ProjectBuilder.builder().build()
        UniminedRepos(project).fabric.ensure()
        assert(UniminedRepos.ensuredOn(project.repositories)
            .any { it.name == "Fabric" })
    }

    @Test
    fun `unrelated handlers do not share the ensured record`() {
        val a = ProjectBuilder.builder().build()
        val b = ProjectBuilder.builder().build()
        UniminedRepos(a).fabric.ensure()
        assert(UniminedRepos.ensuredOn(b.repositories).isEmpty())
    }

    @Test
    fun `validate is quiet when ensured repos remain`() {
        val project = ProjectBuilder.builder().build()
        UniminedRepos(project).mavenCentral.ensure()
        UniminedRepos(project).mojangLibraries.ensure()
        UniminedRepos.validate(project.logger, project)
    }

    @Test
    fun `validate tolerates removed and misordered repos`() {
        val project = ProjectBuilder.builder().build()
        val repos = UniminedRepos(project)
        repos.mojangLibraries.ensure()
        repos.mavenCentral.ensure()
        repos.fabric.ensure()
        // mojang-first ordering + an ensured repo removed — validate
        // must warn, never throw (Problems API falls back to logger
        // output under test fixtures)
        project.repositories.removeAll { it.name == "Fabric" }
        project.repositories.remove(project.repositories
            .first { it.name == "MavenRepo" })
        project.repositories.addLast(
            project.repositories.mavenCentral()
        )
        UniminedRepos.validate(project.logger, project)
    }
}
