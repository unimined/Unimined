package xyz.wagyourtail.unimined

import groovy.lang.Closure
import org.gradle.api.Action
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.gradle.api.internal.project.ProjectInternal
import xyz.wagyourtail.unimined.mapping.task.ExportMappingsTask
import xyz.wagyourtail.unimined.migrate.MigrateExtension
import xyz.wagyourtail.unimined.migrate.task.MigrateMappingsTask
import xyz.wagyourtail.unimined.minecraft.MinecraftExtension
import xyz.wagyourtail.unimined.mod.ModExtension
import xyz.wagyourtail.unimined.remap.FootgunCheckLevel
import xyz.wagyourtail.unimined.remap.RemapExtension
import xyz.wagyourtail.unimined.runs.RunsExtension

/**
 * `unimined.<verb>` forwarding — the facade verbs must execute
 * against the same extension objects the sub-plugins registered
 * (never facades/copies), and the `exportMappings`/`migrateMappings`
 * factory verbs must register their per-env tasks through the
 * owning extensions.
 */
class FrontendForwardingTest {

    private fun project(): ProjectInternal {
        val p = ProjectBuilder.builder().build() as ProjectInternal
        p.plugins.apply("xyz.wagyourtail.unimined")
        return p
    }

    private fun facade(p: ProjectInternal): UniminedExtension =
        p.extensions.getByName("unimined") as UniminedExtension

    @Test
    fun `unimined minecraft remap runs and mod actions execute against the real extensions`() {
        val p = project()
        val facade = facade(p)

        var mcCaptured: MinecraftExtension? = null
        facade.minecraft(Action {
            mcCaptured = it
            it.version.set("1.0")
        })
        assertSame(
            p.extensions.getByType(MinecraftExtension::class.java),
            mcCaptured,
            "unimined.minecraft must delegate to uniminedMinecraft"
        )
        assertEquals("1.0", mcCaptured!!.version.get())

        var remapCaptured: RemapExtension? = null
        facade.remap(Action {
            remapCaptured = it
            it.footgunChecks.set(FootgunCheckLevel.OFF)
        })
        assertSame(
            p.extensions.getByType(RemapExtension::class.java),
            remapCaptured,
            "unimined.remap must delegate to uniminedRemap"
        )
        assertEquals(FootgunCheckLevel.OFF, remapCaptured!!.footgunChecks.get())

        var runsCaptured: RunsExtension? = null
        facade.runs(Action {
            runsCaptured = it
            it.create("ci") { c -> c.mainClass.set("x.Ci") }
        })
        assertSame(
            p.extensions.getByType(RunsExtension::class.java),
            runsCaptured,
            "unimined.runs must delegate to uniminedRuns"
        )
        assertEquals("x.Ci", runsCaptured!!.runs.getByName("ci").mainClass.get())

        var modCaptured: ModExtension? = null
        facade.mod(Action {
            modCaptured = it
            it.id.set("mymod")
        })
        assertSame(
            p.extensions.getByType(ModExtension::class.java),
            modCaptured,
            "unimined.mod must delegate to uniminedMod"
        )
        assertEquals("mymod", modCaptured!!.id.get())
    }

    @Test
    fun `unimined migrate forwards to the migrate extension`() {
        val p = project()
        var captured: MigrateExtension? = null
        facade(p).migrate(Action { captured = it })
        assertSame(
            p.extensions.getByType(MigrateExtension::class.java),
            captured,
            "unimined.migrate must delegate to the migrate extension"
        )
    }

    @Test
    fun `unimined runs and mod groovy closures delegate to their extensions`() {
        val p = project()
        val facade = facade(p)
        val delegates = mutableListOf<Any?>()

        facade.runs(closure(delegates) {
            (it as RunsExtension).enabled.set(false)
        })
        facade.mod(closure(delegates) {
            (it as ModExtension).id.set("gm")
        })

        assertTrue(delegates[0] is RunsExtension,
            "unimined.runs {} must delegate to RunsExtension")
        assertTrue(delegates[1] is ModExtension,
            "unimined.mod {} must delegate to ModExtension")
        assertFalse(
            p.extensions.getByType(RunsExtension::class.java).enabled.get())
        assertEquals("gm",
            p.extensions.getByType(ModExtension::class.java).id.get())
    }

    @Test
    fun `exportMappings registers per-env export tasks through the facade`() {
        val p = project()
        val facade = facade(p)

        facade.exportMappings("main", Action {})
        assertTrue(p.tasks.findByName("exportMappings") is ExportMappingsTask,
            "main env exports as the bare 'exportMappings' task")

        facade.minecraft(Action { it.sourceSet("fabric") {} })
        facade.exportMappings("fabric", Action {})
        assertTrue(
            p.tasks.findByName("exportFabricMappings") is ExportMappingsTask,
            "non-main env exports as 'export<Env>Mappings'"
        )
        assertNotNull(
            p.tasks.findByName("exportMappingsFabric"),
            "pre-convention name kept as the deprecated alias"
        )
    }

    @Test
    fun `migrateMappings registers and configures per-env tasks through the facade`() {
        val p = project()
        val facade = facade(p)

        // realizing the task wires sourceSet.compileClasspath, whose
        // env binding resolves artifacts() — FakeProvider serves the
        // version-1.0 spec so nothing hits a repository
        facade.minecraft(Action {
            it.version.set("1.0")
            it.sourceSet("fabric") { e -> e.version.set("1.0") }
        })

        facade.migrateMappings("main", Action { t ->
            t.target { maven("com.example:target-mappings:1") }
        })
        val main = p.tasks.findByName("migrateMappings")
        assertTrue(main is MigrateMappingsTask,
            "main env migrates as the bare 'migrateMappings' task")
        assertNotNull((main as MigrateMappingsTask).targetSpec,
            "the action's target {} spec must land on the realized task")

        facade.migrateMappings("fabric", Action { t ->
            t.target { maven("com.example:target-mappings:1") }
        })
        assertTrue(
            p.tasks.findByName("migrateFabricMappings") is MigrateMappingsTask,
            "non-main env migrates as 'migrate<Env>Mappings'"
        )
        assertNotNull(
            p.tasks.findByName("migrateMappingsFabric"),
            "pre-convention name kept as the deprecated alias"
        )
    }

    private fun closure(
        delegates: MutableList<Any?>,
        action: (Any?) -> Unit
    ): Closure<Any?> = object : Closure<Any?>(this) {
        fun doCall() {
            val target = getDelegate()
            delegates += target
            action(target)
        }
    }

}
