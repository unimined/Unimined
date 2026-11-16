package xyz.wagyourtail.unimined.ide

import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.gradle.api.tasks.SourceSet
import xyz.wagyourtail.unimined.ide.internal.IdeModelResolver
import xyz.wagyourtail.unimined.minecraft.MinecraftExtension
import xyz.wagyourtail.unimined.runs.RunConfig
import xyz.wagyourtail.unimined.runs.RunsExtension
import xyz.wagyourtail.unimined.transform.TransformExtension
import java.io.File
import java.nio.file.Path

/**
 * `mods { }` launch-side consumption on the IDE path —
 * `IdeModelResolver.resolveRun` must mirror `run<Name>`: selected
 * group files join the classpath model tagged [ClasspathGroup.MODS],
 * the loader's `classpathGroups` capability serializes as a `-D` JVM
 * arg (loom's DLI run-config emit does the same), and `modGroupsEnv`
 * entries merge into the environment map. A fake `testfabric`
 * descriptor supplies both capability channels.
 */
class IdeModsGroupsTest {

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

    private lateinit var idleSet: SourceSet

    private fun envWithMods(project: org.gradle.api.Project): Pair<
            xyz.wagyourtail.unimined.minecraft.MinecraftEnvironment,
            java.io.File> {
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        ext.main.version.set("1.20.1")
        ext.main.provider.set("fake")
        val env = ext.sourceSet("main") {
            it.devNamespace.set("named")
            it.modSourceNamespaces.set(listOf("named"))
            it.loader("testfabric", "1.0")
        }
        val modJar = dir.resolve("modfiles/testmod.jar").toFile()
        modJar.parentFile.mkdirs()
        modJar.writeBytes(byteArrayOf(0x50, 0x4B, 0x05, 0x06, *ByteArray(18)))
        // a dedicated source set stands in for the mod's own sources —
        // its runtimeClasspath has no env product wiring (env product
        // confs run artifact transforms that can't isolate params under
        // ProjectBuilder), and claiming it as the "ss" group's member
        // means every dev-classpath file short-circuits into the MODS
        // bucket before groupOf() has to resolve product views
        idleSet = project.extensions.getByType(
            org.gradle.api.tasks.SourceSetContainer::class.java)
            .create("idletest")
        env.mods { mods ->
            mods.register("ss") { it.sourceSet(idleSet) }
            mods.register("jarMod") { it.modFiles(modJar) }
        }
        return env to modJar
    }

    private fun resolve(
        project: org.gradle.api.Project,
        env: xyz.wagyourtail.unimined.minecraft.MinecraftEnvironment,
        run: RunConfig
    ): IdeRunModel =
        IdeModelResolver.resolveRun(
            project, env, run, idleSet,
            project.extensions.getByType(MinecraftExtension::class.java),
            project.extensions.getByType(TransformExtension::class.java)
        ).get()

    @Test
    fun `resolveRun serializes mod groups into jvm args env and classpath`() {
        val project = project()
        val (env, modJar) = envWithMods(project)
        val runs = project.extensions.getByType(RunsExtension::class.java)
        val run = runs.runs.maybeCreate("client")
        run.systemProperties.put("custom", "v")

        val model = resolve(project, env, run)
        val sep = File.pathSeparator

        // classpathGroups capability → -Dfabric.classPathGroups=<paths>
        // — groups doubled-separator apart, files single
        val groupArg = model.jvmArgs.singleOrNull {
            it.startsWith("-Dfabric.classPathGroups=") }
        assertNotNull(groupArg,
            "classpathGroups capability must land as a -D arg: ${model.jvmArgs}")
        val value = groupArg!!.substringAfter("=")
        assertTrue(modJar.absolutePath in value,
            "jarMod group's jar serializes: $value")
        assertTrue(value.contains(sep + sep),
            "two declared groups must produce two sections: $value")

        // user systemProperties ride the same -D channel
        assertTrue("-Dcustom=v" in model.jvmArgs)

        // modGroupsEnv capability → MOD_CLASSES `id%%path` entries
        val modClasses = model.environmentVariables["MOD_CLASSES"]
        assertNotNull(modClasses,
            "modGroupsEnv capability must land in the env map")
        assertTrue("jarMod%%${modJar.absolutePath}" in modClasses!!,
            "jarMod entry: $modClasses")
        assertTrue(modClasses.contains("ss%%"), "ss entry: $modClasses")

        // group files join the classpath model tagged MODS
        val jarEntry = model.classpath.singleOrNull {
            it.jar == modJar.absolutePath }
        assertNotNull(jarEntry,
            "modFiles jar must be on the IDE classpath model")
        assertEquals(ClasspathGroup.MODS, jarEntry!!.group)
    }

    @Test
    fun `resolveRun loadedMods subset serializes only the selected groups`() {
        val project = project()
        val (env, modJar) = envWithMods(project)
        val runs = project.extensions.getByType(RunsExtension::class.java)
        val run = runs.runs.maybeCreate("client")
        run.loadedMods.set(setOf("ss"))

        val model = resolve(project, env, run)

        val value = model.jvmArgs.single {
            it.startsWith("-Dfabric.classPathGroups=") }
            .substringAfter("=")
        assertFalse(modJar.absolutePath in value,
            "unselected group must be absent: $value")
        assertFalse(value.contains(File.pathSeparator + File.pathSeparator),
            "one selected group serializes as a lone section: $value")
        val modClasses = model.environmentVariables["MOD_CLASSES"]
        assertNotNull(modClasses)
        assertTrue(modClasses!!.contains("ss%%"))
        assertFalse("jarMod%%" in modClasses)
        // unselected files are off the classpath model too
        assertTrue(model.classpath.none { it.jar == modJar.absolutePath })
    }

    // no prod test here — the prod classpath resolves the env's
    // transformed prod view, which can't isolate transform params
    // under ProjectBuilder. `ModGroups.selected` returning an empty
    // selection on prod is covered by ModGroupsTest in
    // unimined-minecraft (`prod launches select no mod groups`).

}
