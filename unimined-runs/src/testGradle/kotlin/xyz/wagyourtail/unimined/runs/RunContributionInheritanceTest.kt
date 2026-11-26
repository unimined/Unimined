package xyz.wagyourtail.unimined.runs

import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import xyz.wagyourtail.unimined.minecraft.MinecraftExtension
import xyz.wagyourtail.unimined.runs.task.MinecraftRunTask
import java.nio.file.Path

/**
 * Run-contribution inheritance across combined environments
 * (upstream issue #221): loader wirings register their run
 * contributions on their own environment's transform spec, so a
 * per-loader env combining a multi-loader `main` must not inherit
 * contributions the parent's *other* loader registered — fabric's
 * `-Dfabric.development` has no business on a forge run. The
 * launched env always wires its own loader's contributions directly,
 * so filtering costs nothing; loaderless (cross-cutting/user)
 * contributions and same-loader chains (split/aux envs) still
 * inherit.
 */
class RunContributionInheritanceTest {

    @TempDir
    lateinit var dir: Path

    private fun project() = ProjectBuilder.builder()
        .withProjectDir(dir.toFile())
        .build()
        .also { project ->
            project.plugins.apply("java")
            project.plugins.apply("xyz.wagyourtail.unimined.remap")
            project.plugins.apply("xyz.wagyourtail.unimined.minecraft")
            project.plugins.apply("xyz.wagyourtail.unimined.transform")
            project.plugins.apply("xyz.wagyourtail.unimined.runs")
        }

    private fun evaluate(project: org.gradle.api.Project) {
        (project as org.gradle.api.internal.project.ProjectInternal)
            .evaluate()
    }

    private fun mutatedJvmArgs(project: org.gradle.api.Project, taskName: String): List<String> {
        val task = project.tasks.findByName(taskName) as MinecraftRunTask
        return task.mutatedJvmArgs.get()
    }

    /** The issue scenario: `main` is a merged multi-loader env
     *  (testfabric primary, testforge secondary) whose transform spec
     *  carries the primary loader's run contributions; a per-loader
     *  `forge` env combines it through the implicit parent edge.
     *  main's fabric-shaped contribution must not leak into
     *  `runForgeClient` — the forge env's own contribution is the
     *  only one that applies. */
    @Test
    fun `mismatched-loader parent contributions do not leak into child runs`() {
        val project = project()
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        project.extensions.getByType(org.gradle.api.tasks.SourceSetContainer::class.java)
            .create("forge")
        ext.sourceSet("main") { env ->
            env.version.set("1.20.1")
            env.loader("testfabric", "1.0")
            env.loader("testforge", "1.0")
        }
        ext.sourceSet("forge") { env ->
            env.version.set("1.20.1")
            env.loader("testforge", "1.0")
        }

        // stand-ins for loader wiring: fabric-family wiring registers
        // on `main` (where its self-gate passes — main's primary is
        // fabric), forge-family wiring on the `forge` env
        val transform = project.extensions.getByType(
            xyz.wagyourtail.unimined.transform.TransformExtension::class.java)
        transform.sourceSet("main").runMutators += org.gradle.api.Action { spec ->
            spec.jvmArgs += "-Dfabric.development=true"
        }
        transform.sourceSet("forge").runMutators += org.gradle.api.Action { spec ->
            spec.jvmArgs += "-DlegacyClassPath=own"
        }
        evaluate(project)

        // main's own runs keep main's contribution
        assertTrue("-Dfabric.development=true" in
            mutatedJvmArgs(project, "runClient"))

        // the forge env's run carries only its own loader's shaping
        val forgeArgs = mutatedJvmArgs(project, "runForgeClient")
        assertTrue("-DlegacyClassPath=own" in forgeArgs,
            "own loader contribution must apply: $forgeArgs")
        assertFalse("-Dfabric.development=true" in forgeArgs,
            "mismatched-loader parent contribution must not inherit: $forgeArgs")
    }

    /** Same-loader inheritance stays: an env whose loader matches the
     *  parent's still inherits the parent's contributions — the
     *  split-client/aux-env shape runs rely on (the launched env
     *  wires its own copy too, but nothing may be filtered out). */
    @Test
    fun `same-loader parent contributions still inherit`() {
        val project = project()
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        project.extensions.getByType(org.gradle.api.tasks.SourceSetContainer::class.java)
            .create("fabric2")
        ext.sourceSet("main") { env ->
            env.version.set("1.20.1")
            env.loader("testfabric", "1.0")
        }
        ext.sourceSet("fabric2") { env ->
            env.version.set("1.20.1")
            env.loader("testfabric", "1.0")
        }
        val transform = project.extensions.getByType(
            xyz.wagyourtail.unimined.transform.TransformExtension::class.java)
        transform.sourceSet("main").runMutators += org.gradle.api.Action { spec ->
            spec.jvmArgs += "-Dfabric.development=true"
        }
        evaluate(project)

        assertTrue("-Dfabric.development=true" in
            mutatedJvmArgs(project, "runFabric2Client"),
            "same-loader parent contribution must keep inheriting")
    }

    /** Loaderless parents carry no loader attribution — their
     *  contributions are the cross-cutting/user channel (a common
     *  env's shaping every per-loader env wants) and pass through
     *  regardless of the child env's loader. */
    @Test
    fun `loaderless parent contributions pass through`() {
        val project = project()
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        project.extensions.getByType(org.gradle.api.tasks.SourceSetContainer::class.java)
            .create("forge")
        ext.sourceSet("main") { env ->
            env.version.set("1.20.1")
        }
        ext.sourceSet("forge") { env ->
            env.version.set("1.20.1")
            env.loader("testforge", "1.0")
        }
        val transform = project.extensions.getByType(
            xyz.wagyourtail.unimined.transform.TransformExtension::class.java)
        transform.sourceSet("main").runMutators += org.gradle.api.Action { spec ->
            spec.jvmArgs += "-Duser.crosscutting=true"
        }
        evaluate(project)

        assertTrue("-Duser.crosscutting=true" in
            mutatedJvmArgs(project, "runForgeClient"),
            "loaderless parent contributions are cross-cutting")
    }

    /** Loader-attributed contributions must not land on a loaderless
     *  child — the child has no loader jar on its classpath, so the
     *  parent's loader shaping can never apply to it. */
    @Test
    fun `loader contributions do not leak into loaderless child runs`() {
        val project = project()
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        project.extensions.getByType(org.gradle.api.tasks.SourceSetContainer::class.java)
            .create("plain")
        ext.sourceSet("main") { env ->
            env.version.set("1.20.1")
            env.loader("testfabric", "1.0")
        }
        ext.sourceSet("plain") { env ->
            env.version.set("1.20.1")
        }
        val transform = project.extensions.getByType(
            xyz.wagyourtail.unimined.transform.TransformExtension::class.java)
        transform.sourceSet("main").runMutators += org.gradle.api.Action { spec ->
            spec.jvmArgs += "-Dfabric.development=true"
        }
        evaluate(project)

        assertFalse("-Dfabric.development=true" in
            mutatedJvmArgs(project, "runPlainClient"),
            "loader-attributed contribution must not land on a loaderless env")
    }

    /** Split envs — the load-bearing inheritance case: client runs
     *  retarget onto the split `client` env, whose loader matches
     *  main's through section inheritance, so main's contributions
     *  must keep applying or every fabric/forge split launch loses
     *  its loader shaping. */
    @Test
    fun `split client runs keep the parent loader contributions`() {
        val project = project()
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        ext.sourceSet("main") { env ->
            env.version.set("1.20.1")
            env.loader("testfabric", "1.0")
        }
        ext.splitEnvironmentSourceSets()
        val transform = project.extensions.getByType(
            xyz.wagyourtail.unimined.transform.TransformExtension::class.java)
        transform.sourceSet("main").runMutators += org.gradle.api.Action { spec ->
            spec.jvmArgs += "-Dfabric.development=true"
        }
        evaluate(project)

        assertTrue("-Dfabric.development=true" in
            mutatedJvmArgs(project, "runClient"),
            "split client run must keep main's same-loader contributions")
    }
}
