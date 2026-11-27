package xyz.wagyourtail.unimined.minecraft

import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

/**
 * [ModGroups] launch-side serialization: `loadedMods` selection (and
 * the prod-launch gate), the fabric-family `classpathGroups` value
 * (doubled path separator between groups, single between files),
 * forge `MOD_CLASSES` `id%%path` entries, and the merge that layers
 * them under the loader's own contribution.
 */
class ModGroupsTest {

    @TempDir
    lateinit var dir: Path

    private fun project() = ProjectBuilder.builder()
        .withProjectDir(dir.toFile())
        .build()
        .also { project ->
            project.plugins.apply("java")
            project.plugins.apply("xyz.wagyourtail.unimined.minecraft")
        }

    private fun envWithMods(
        project: org.gradle.api.Project,
        block: (org.gradle.api.NamedDomainObjectContainer<ModGroupSpec>) -> Unit
    ): MinecraftEnvironment {
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        val env = ext.sourceSet("main")
        env.mods { block(it) }
        return env
    }

    @Test
    fun `empty loadedMods selects every declared group, subset filters`() {
        val project = project()
        val env = envWithMods(project) { mods ->
            mods.register("a") { it.sourceSet("main") }
            mods.register("b") { it.sourceSet("main") }
        }
        assertEquals(
            listOf("a", "b"),
            ModGroups.selected(env, emptySet(), prod = false)
                .map { it.name })
        assertEquals(
            listOf("b"),
            ModGroups.selected(env, setOf("b"), prod = false)
                .map { it.name })
    }

    @Test
    fun `prod launches select no mod groups`() {
        val project = project()
        val env = envWithMods(project) { mods ->
            mods.register("a") { it.sourceSet("main") }
        }
        assertTrue(ModGroups.selected(env, emptySet(), prod = true)
            .isEmpty(),
            "mods{} groups are a dev-launch channel — prod selects none")
    }

    @Test
    fun `classpathGroupsValue separates groups by doubled path separator`() {
        val project = project()
        val env = envWithMods(project) { mods ->
            mods.register("a") { it.sourceSet("main") }
            mods.register("b") {
                it.modFiles(dir.resolve("modB.jar").toFile())
            }
        }
        val groups = ModGroups.selected(env, emptySet(), prod = false)
        val sep = File.pathSeparator
        val value = ModGroups.classpathGroupsValue(groups)
        val parts = value.split(sep + sep)
        assertEquals(2, parts.size,
            "each declared group becomes one section: $value")
        // group a = main's classes+resources dirs (single-sep joined);
        // group b = the modFiles jar only
        assertTrue("main" in parts[0])
        assertTrue(
            parts[1].endsWith("modB.jar"),
            "modFiles jar must serialize as a group path: $value")
    }

    @Test
    fun `modClassesEntries emits id-pct-pct-path pairs`() {
        val project = project()
        val jarFile = dir.resolve("mod.jar").toFile()
        val env = envWithMods(project) { mods ->
            // ':'s flatten — 1.x `parent.groups` shape keeps the
            // env-var entry parseable
            mods.register("sub:mod") { it.modFiles(jarFile) }
        }
        val entries = ModGroups.modClassesEntries(
            ModGroups.selected(env, emptySet(), prod = false))
        assertEquals(
            listOf("sub_mod%%${jarFile.absolutePath}"), entries)
    }

    @Test
    fun `modClassesEntries puts a metadata-bearing resource root first`() {
        val classes = dir.resolve("classes").toFile().apply { mkdirs() }
        val resources = dir.resolve("resources").toFile().apply {
            resolve("META-INF").mkdirs()
            resolve("META-INF/mods.toml").writeText("modLoader=\"javafml\"")
        }
        val trailing = dir.resolve("trailing").toFile().apply { mkdirs() }

        assertEquals(
            listOf(resources, classes, trailing),
            ModGroups.metadataFirst(listOf(classes, resources, trailing)),
            "the resource root carrying mods.toml must lead; unrelated order stays stable")
    }

    @Test
    fun `mergeModClasses unions entries by label`() {
        val sep = File.pathSeparator
        // entries sharing a label interleave into one group — FML/
        // bootstrap-dev group purely by the `%%` prefix, so a split
        // union would split the mod into two modfiles
        assertEquals(
            "omnilook%%a${sep}omnilook%%b",
            ModGroups.mergeModClasses("omnilook%%a", "omnilook%%b"))
        // distinct labels keep declaration order
        assertEquals(
            "x%%a${sep}y%%b",
            ModGroups.mergeModClasses("x%%a", "y%%b"))
        // duplicates within a label collapse
        assertEquals(
            "x%%a",
            ModGroups.mergeModClasses("x%%a", "x%%a"))
        // raw paths fall under the runtimes' own defaultmodid fallback
        assertEquals(
            "defaultmodid%%base${sep}defaultmodid%%ours",
            ModGroups.mergeModClasses("base", "ours"))
        assertEquals("defaultmodid%%ours",
            ModGroups.mergeModClasses(null, "ours"))
        assertEquals("defaultmodid%%base",
            ModGroups.mergeModClasses("base", ""))
        assertEquals("", ModGroups.mergeModClasses(null, ""))
    }

    @Test
    fun `mergeModClasses orders metadata roots first and manifest member last`() {
        val sep = File.pathSeparator
        val metadataDir = dir.resolve("res").toFile().apply {
            resolve("META-INF").mkdirs()
            resolve("META-INF/mods.toml").writeText("modLoader=\"javafml\"")
        }
        val plain = dir.resolve("classes").toFile().apply { mkdirs() }
        val manifest = dir.resolve("mod-classes").toFile().apply { mkdirs() }
        // plain dir leading in the input must reorder behind the
        // metadata root; the manifest member trails even when it
        // didn't arrive last
        val merged = ModGroups.mergeModClasses(
            "omnilook%%${plain.absolutePath}${sep}omnilook%%${manifest.absolutePath}",
            "omnilook%%${metadataDir.absolutePath}")
        assertEquals(
            "omnilook%%${metadataDir.absolutePath}" +
                "${sep}omnilook%%${plain.absolutePath}" +
                "${sep}omnilook%%${manifest.absolutePath}",
            merged)
    }

    @Test
    fun `effectiveModGroups lets a redeclared child group shadow the inherited one`() {
        val project = project()
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        val parentJar = dir.resolve("parent.jar").toFile()
        val childJar = dir.resolve("child.jar").toFile()
        val parent = project.objects.newInstance(
            ModGroupSpec::class.java, "shared", project
        ).also { it.modFiles(parentJar) }
        val env = ext.sourceSet("main")
        env.inheritedModSpecs += parent
        env.mods { mods ->
            mods.register("shared") { it.modFiles(childJar) }
            mods.register("parentOnly") { it.modFiles(parentJar) }
        }
        val effective = env.effectiveModGroups()
        assertEquals(setOf("shared", "parentOnly"),
            effective.map { it.name }.toSet(),
            "own declaration shadows the inherited same-name group")
        assertEquals(listOf(childJar),
            effective.first { it.name == "shared" }.launchFiles(),
            "the shadowing group's files win")
    }

}
