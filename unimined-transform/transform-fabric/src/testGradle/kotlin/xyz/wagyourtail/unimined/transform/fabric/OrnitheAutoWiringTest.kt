package xyz.wagyourtail.unimined.transform.fabric

import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import xyz.wagyourtail.unimined.api.spec.Side
import xyz.wagyourtail.unimined.mapping.MappingsSpecImpl
import xyz.wagyourtail.unimined.minecraft.MinecraftEnvironment
import xyz.wagyourtail.unimined.minecraft.MinecraftExtension
import xyz.wagyourtail.unimined.remap.RemapExtension
import xyz.wagyourtail.unimined.remap.spi.DevArtifactOnlyStepSpec
import xyz.wagyourtail.unimined.remap.spi.RemapSurface
import xyz.wagyourtail.unimined.remap.spi.StepScope
import xyz.wagyourtail.unimined.remapper.uml.CondorStepSpec
import xyz.wagyourtail.unimined.remapper.uml.PreenStepSpec
import xyz.wagyourtail.unimined.transform.fabric.internal.OrnitheAutoWiring
import java.nio.file.Files
import java.nio.file.Path

/**
 * Ploceus-parity coverage for the Ornithe auto contributions: the
 * version-gated `condor` LVT pass, unconditional `preen`, the
 * `disableX()` opt-outs, non-ornithe exclusion, and the auto `nests`
 * enricher source resolved off a local meta index
 * (`unimined.ornitheMeta` override — no live meta or maven access).
 */
class OrnitheAutoWiringTest {

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
            project.plugins.apply("xyz.wagyourtail.unimined.transform.fabric")
        }

    /** Writes `<dir>/meta/v3/versions` and returns the `file:` base —
     *  set as `unimined.ornitheMeta` so index lookups stay local. */
    private fun serveMeta(body: String): String {
        val meta = dir.resolve("meta")
        Files.createDirectories(meta.resolve("v3"))
        Files.writeString(meta.resolve("v3/versions"), body)
        return meta.toUri().toString()
    }

    private fun nestsEntry(
        version: String, gameVersion: String, environment: String, build: Int
    ) = """{"version": "$version", "stable": true, """ +
            """"maven": "net.ornithemc:nests:$version", """ +
            """"gameVersion": "$gameVersion", """ +
            """"environment": "$environment", "build": $build}"""

    private fun nestsIndex(vararg entries: String) =
        """{"nests": [${entries.joinToString(",")}]}"""

    private fun env(
        project: org.gradle.api.Project,
        ext: MinecraftExtension,
        id: String,
        mc: String,
        loader: String = "ornithe",
        side: Side = Side.MERGED,
        configure: MinecraftEnvironment.() -> Unit = {}
    ): MinecraftEnvironment {
        project.extensions.getByType(
            org.gradle.api.tasks.SourceSetContainer::class.java
        ).maybeCreate(id)
        return ext.sourceSet(id) { e ->
            // asserting this env's own contributions — skip the
            // implicit-parent combine entirely
            e.inheritParent.set(false)
            e.version.set(mc)
            e.side.set(side)
            e.loader(loader, "1.0")
            e.configure()
        }
    }

    private fun autoSteps(
        project: org.gradle.api.Project, env: MinecraftEnvironment
    ) = project.extensions.getByType(RemapExtension::class.java)
        .sourceSet(env.environmentId)
        .contributedSteps(RemapSurface.JAR, StepScope.ENVIRONMENT_ARTIFACT)

    /** Invokes the auto-nests deferred contribution directly — the
     *  lambda is registered last (after the fabric intermediary
     *  contribution); evaluating `effectiveSources()` would pull the
     *  product-resolution path instead of just this contribution. */
    private fun autoNests(env: MinecraftEnvironment) =
        env.mappingsSpec.deferredDefaultSources.last()
            .invoke(env.mappingsSpec)

    @Test
    fun `shouldPatchLvts matches the ploceus normalized window`() {
        // inside (0.32.0, 1.8.2-rc.5): all alpha/beta and everything
        // released before 1.8.2, including 1.8.2's own prereleases
        for (v in listOf(
            "a1.1.2", "a1.2.6", "b1.7.3", "b1.8.1", "1.0", "1.4.7",
            "1.7.10", "1.8", "1.8.1", "1.8.2-pre5", "12w05a", "14w02a",
            "15w13a", "empty-b1.7.3"
        )) {
            assertTrue(
                OrnitheAutoWiring.shouldPatchLvts(v),
                "$v should be inside the LVT window"
            )
        }
        // <= 0.32.0 (indev/infdev/classic/pre-classic — condor's frame
        // analysis is broken) and >= 1.8.2-rc.5 (real LVTs ship again)
        for (v in listOf(
            "rd-132211", "c0.30_01c", "in-20100214", "inf-20100618",
            "1.8.2", "1.8.3", "1.8.8", "1.9", "1.9-pre1", "1.20.1",
            "15w14a", "15w31a", "18w01a", "empty-1.8.8", "combat3",
            "3D-Shareware", "1.RV-Pre1"
        )) {
            assertFalse(
                OrnitheAutoWiring.shouldPatchLvts(v),
                "$v should be outside the LVT window"
            )
        }
    }

    @Test
    fun `ornithe env in the lvt window gets condor and preen on the env artifact`() {
        val project = project()
        // keep the descriptor's meta overlay fetch local too —
        // `unimined.ornitheMeta` overrides the base URL
        project.extensions.extraProperties.set(
            "unimined.ornitheMeta", serveMeta("{}")
        )
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        val env = env(project, ext, "ornithe", "1.7.10")
        (project as org.gradle.api.internal.project.ProjectInternal).evaluate()

        val steps = autoSteps(project, env)
        assertEquals(2, steps.size)
        // ploceus processor order: LVT before preen
        assertTrue(steps[0] is CondorStepSpec)
        assertTrue(steps[1] is PreenStepSpec)
        // ploceus processors only see MC jars — the specs mark
        // DevArtifactOnly so remapJar/dep pipelines skip them
        assertTrue(steps[0] is DevArtifactOnlyStepSpec)
        assertTrue(steps[1] is DevArtifactOnlyStepSpec)

        // they ride after the access steps like ploceus jar processors
        // on the named jar
        val all = project.extensions.getByType(RemapExtension::class.java)
            .sourceSet("ornithe").contributedSteps(RemapSurface.JAR)
        val condorIdx = all.indexOfFirst { it is CondorStepSpec }
        val preenIdx = all.indexOfFirst { it is PreenStepSpec }
        assertTrue(condorIdx >= 0 && preenIdx > condorIdx)
    }

    @Test
    fun `ornithe env outside the lvt window gets preen only`() {
        val project = project()
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        val env = env(project, ext, "ornithe", "1.8.8")
        (project as org.gradle.api.internal.project.ProjectInternal).evaluate()
        val steps = autoSteps(project, env)
        assertEquals(1, steps.size)
        assertTrue(steps.single() is PreenStepSpec)
    }

    @Test
    fun `disable opt-outs suppress their auto steps`() {
        val project = project()
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        val env = env(project, ext, "ornithe", "1.7.10") {
            disableLvtPatch()
            disablePreen()
        }
        (project as org.gradle.api.internal.project.ProjectInternal).evaluate()
        assertTrue(autoSteps(project, env).isEmpty())
    }

    @Test
    fun `non-ornithe fabric-family loaders get no auto steps`() {
        val project = project()
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        // declare all envs ahead of evaluation — evaluate() runs once
        val envs = listOf("fabric", "quilt", "legacyfabric", "babric")
            .associateWith { loader ->
                env(project, ext, loader, "1.7.10", loader = loader)
            }
        (project as org.gradle.api.internal.project.ProjectInternal)
            .evaluate()
        for ((loader, env) in envs) {
            assertTrue(
                autoSteps(project, env).isEmpty(),
                "$loader should get no ornithe auto steps"
            )
        }
    }

    @Test
    fun `auto nests contributes latest builds per side on split-era versions`() {
        val project = project()
        project.extensions.extraProperties.set(
            "unimined.ornitheMeta", serveMeta(
                nestsIndex(
                    nestsEntry("b1.7.3-client+build.6", "b1.7.3", "client", 6),
                    nestsEntry("b1.7.3-client+build.7", "b1.7.3", "client", 7),
                    nestsEntry("b1.7.3-server+build.2", "b1.7.3", "server", 2),
                )
            )
        )
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        val env = env(project, ext, "ornithe", "b1.7.3")
        val sources = autoNests(env)
        assertEquals(2, sources.size)
        val notations = sources.map {
            (it as MappingsSpecImpl.Source.Maven).notation
        }
        // per-side latest: client picks build.7, server build.2
        assertEquals(
            listOf(
                "net.ornithemc:nests:b1.7.3-client+build.7",
                "net.ornithemc:nests:b1.7.3-server+build.2"
            ), notations
        )
        // enricher grafts `source` onto the per-side official columns
        assertEquals(
            listOf("official:client", "official:server"),
            sources.map { it.nsMap["source"] }
        )
        // declaring split-era enrichers marks the spec like a manual
        // `nests(...)` would
        assertTrue(env.mappingsSpec.splitOfficial)
        assertEquals(
            listOf("official:client", "official:server"),
            env.mappingsSpec.splitOfficialNamespaces
        )
    }

    @Test
    fun `auto nests picks the merged artifact on non-split versions`() {
        val project = project()
        project.extensions.extraProperties.set(
            "unimined.ornitheMeta", serveMeta(
                nestsIndex(nestsEntry("1.7.10+build.6", "1.7.10", "*", 6))
            )
        )
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        val env = env(project, ext, "ornithe", "1.7.10")
        val sources = autoNests(env)
        assertEquals(1, sources.size)
        assertEquals(
            "net.ornithemc:nests:1.7.10+build.6",
            (sources.single() as MappingsSpecImpl.Source.Maven).notation
        )
        assertEquals("official", sources.single().nsMap["source"])
        assertFalse(env.mappingsSpec.splitOfficial)
    }

    @Test
    fun `sided env falls back to the merged nests artifact`() {
        val project = project()
        project.extensions.extraProperties.set(
            "unimined.ornitheMeta", serveMeta(
                nestsIndex(nestsEntry("1.7.10+build.6", "1.7.10", "*", 6))
            )
        )
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        // no `1.7.10-client` artifact exists — a client env takes the
        // merged publication, same as ploceus's Simple provider
        val env = env(project, ext, "ornithe", "1.7.10", side = Side.CLIENT)
        val sources = autoNests(env)
        assertEquals(1, sources.size)
        assertEquals(
            "net.ornithemc:nests:1.7.10+build.6",
            (sources.single() as MappingsSpecImpl.Source.Maven).notation
        )
    }

    @Test
    fun `auto nests no-ops when nothing is published or nests is opted out`() {
        val project = project()
        project.extensions.extraProperties.set(
            "unimined.ornitheMeta", serveMeta(
                nestsIndex(
                    nestsEntry("b1.7.3-client+build.7", "b1.7.3", "client", 7)
                )
            )
        )
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        // no nests entries for 1.8.8 — inert like ploceus's absent provider
        val noNests = env(project, ext, "ornithe", "1.8.8")
        assertTrue(autoNests(noNests).isEmpty())
        // disableNests() suppresses the contribution entirely
        val disabled = env(project, ext, "ornithe2", "b1.7.3") {
            disableNests()
        }
        assertTrue(autoNests(disabled).isEmpty())
    }

    @Test
    fun `auto nests yields to a user-declared nests source`() {
        val project = project()
        project.extensions.extraProperties.set(
            "unimined.ornitheMeta", serveMeta(
                nestsIndex(
                    nestsEntry("b1.7.3-client+build.7", "b1.7.3", "client", 7)
                )
            )
        )
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        val env = env(project, ext, "ornithe", "b1.7.3") {
            mappingsSpec.nests(3)
        }
        assertTrue(autoNests(env).isEmpty())
    }

    @Test
    fun `auto nests no-ops for other fabric loaders and unreachable meta`() {
        val project = project()
        project.extensions.extraProperties.set(
            "unimined.ornitheMeta", serveMeta(
                nestsIndex(
                    nestsEntry("b1.7.3-client+build.7", "b1.7.3", "client", 7)
                )
            )
        )
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        val fabric = env(project, ext, "fabric", "b1.7.3", loader = "fabric")
        assertTrue(autoNests(fabric).isEmpty())
        // unreachable meta (bad file path) — warns once, stays inert
        project.extensions.extraProperties.set(
            "unimined.ornitheMeta", "file:///nonexistent-unimined-meta"
        )
        val unreachable = env(project, ext, "ornithe", "b1.7.3")
        assertTrue(autoNests(unreachable).isEmpty())
    }

}
