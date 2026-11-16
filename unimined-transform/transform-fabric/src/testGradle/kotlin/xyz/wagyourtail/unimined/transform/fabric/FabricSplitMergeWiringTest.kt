package xyz.wagyourtail.unimined.transform.fabric

import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import xyz.wagyourtail.unimined.api.spec.Side
import xyz.wagyourtail.unimined.minecraft.MinecraftEnvironment
import xyz.wagyourtail.unimined.minecraft.MinecraftExtension
import java.nio.file.Path

/**
 * Split-era merged-env marking: every `intermediaryCoords` fabric
 * descriptor declares `splitMerge`, so a MERGED (or COMMON) env on a
 * pre-1.3 version gets the 1.x remap-then-merge hop via
 * `markSplitOfficial` — and non-split/sided/`empty-<post-merge>` envs
 * stay unmarked.
 */
class FabricSplitMergeWiringTest {

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

    private fun env(
        project: org.gradle.api.Project,
        ext: MinecraftExtension,
        id: String,
        mc: String,
        loader: String,
        side: Side = Side.MERGED
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
        }
    }

    @Test
    fun `merged split-era envs on coord-fed fabric loaders mark splitOfficial`() {
        val project = project()
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        // every coord-fed descriptor declares the hop — no published
        // pre-1.3 intermediary exists for these loaders, but nothing
        // version-gates the combo and `customIntermediaries` can
        // supply split columns; the mark is what engages the hop
        val envs = listOf("fabric", "quilt", "legacyfabric", "babric")
            .associateWith { loader ->
                env(project, ext, loader, "1.2.5", loader = loader)
            }
        // markSplitOfficial rides the intermediary contribution's
        // afterDslEval — evaluate() fires it
        (project as org.gradle.api.internal.project.ProjectInternal)
            .evaluate()
        for ((loader, env) in envs) {
            assertTrue(
                env.mappingsSpec.splitOfficial,
                "$loader should mark splitOfficial"
            )
            assertEquals(
                listOf("clientOfficial", "serverOfficial"),
                env.mappingsSpec.splitOfficialNamespaces,
                "$loader splitOfficialNamespaces"
            )
            assertEquals(
                "intermediary", env.mappingsSpec.splitMergeNamespace,
                "$loader splitMergeNamespace"
            )
        }
    }

    @Test
    fun `non-split versions and sided envs stay unmarked`() {
        val project = project()
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        val envs = mapOf(
            "post-merge version" to
                env(project, ext, "lfpost", "1.8.9", loader = "legacyfabric"),
            "client side" to env(
                project, ext, "lfclient", "1.2.5",
                loader = "legacyfabric", side = Side.CLIENT
            ),
            "server side" to env(
                project, ext, "lfserver", "1.2.5",
                loader = "legacyfabric", side = Side.SERVER
            ),
        )
        (project as org.gradle.api.internal.project.ProjectInternal)
            .evaluate()
        for ((case, env) in envs) {
            assertFalse(
                env.mappingsSpec.splitOfficial,
                "$case should stay unmarked"
            )
            assertNull(env.mappingsSpec.splitMergeNamespace, case)
        }
    }

    @Test
    fun `empty- pseudo versions take the wrapped version's era`() {
        val project = project()
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        // `empty-b1.7.3` models a split-era game — mark; `empty-1.8.8`
        // is post-merge — the raw `empty-` id is uncomparable and must
        // not fall into the uncomparable->split default
        val split = env(
            project, ext, "babempty", "empty-b1.7.3", loader = "babric"
        )
        val merged = env(
            project, ext, "fabempty", "empty-1.8.8", loader = "fabric"
        )
        (project as org.gradle.api.internal.project.ProjectInternal)
            .evaluate()
        assertTrue(split.mappingsSpec.splitOfficial)
        assertFalse(merged.mappingsSpec.splitOfficial)
    }

}
