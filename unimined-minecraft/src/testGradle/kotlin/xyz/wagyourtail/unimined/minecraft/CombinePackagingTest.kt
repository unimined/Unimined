package xyz.wagyourtail.unimined.minecraft

import groovy.lang.GroovyShell
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import xyz.wagyourtail.unimined.api.environment.EnvironmentSection
import xyz.wagyourtail.unimined.minecraft.fakes.FakeProvider
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarFile

/**
 * `combineWith` packaging modes — the `shadow` default (flat embed,
 * pre-modes behavior), `"none"` (classpath composition only, artifact
 * carries the env's own output), and the declared-but-unimplemented
 * `"jarinjar"` — plus lazy string validation, chain propagation, and
 * Groovy dispatch. The artifact-level assertions execute the env jar
 * task's copy action for real: the exclusion must cover the packaged
 * artifact, not just the file-collection view.
 */
class CombinePackagingTest {

    @TempDir
    lateinit var dir: Path

    private fun project() = ProjectBuilder.builder()
        .withProjectDir(dir.toFile())
        .build()
        .also {
            // registers JavaToolchainService — real consumers apply java
            // themselves; the plugin does not
            it.plugins.apply("java")
            it.plugins.apply(GradlePlugin::class.java)
        }

    private fun evaluate(project: org.gradle.api.Project) =
        (project as org.gradle.api.internal.project.ProjectInternal).evaluate()

    private fun sourceSets(project: org.gradle.api.Project) =
        project.extensions.getByType(org.gradle.api.tasks.SourceSetContainer::class.java)

    private fun minecraft(project: org.gradle.api.Project) =
        project.extensions.getByType(MinecraftExtension::class.java)

    /** Write one marker file into the env source set's classes and
     *  resources output dirs so jar contents are observable. */
    private fun writeOutputs(project: org.gradle.api.Project, envId: String) {
        val output = sourceSets(project).getByName(envId).output
        output.classesDirs.files.forEach { classes ->
            Files.write(
                classes.toPath().resolve("com/example/$envId/Entry.class")
                    .also { Files.createDirectories(it.parent) },
                byteArrayOf(1)
            )
        }
        output.resourcesDir?.let { res ->
            Files.write(
                res.toPath().resolve("$envId.txt")
                    .also { Files.createDirectories(it.parent) },
                byteArrayOf(2)
            )
        }
    }

    private fun jarEntries(project: org.gradle.api.Project, taskName: String): Set<String> {
        val jar = project.tasks.getByName(taskName) as org.gradle.jvm.tasks.Jar
        // AbstractCopyTask.copy() is protected — RemapJarTask re-exposes
        // it publicly (RemapTaskTest calls it directly), a plain Jar
        // needs the reflective route to execute outside the task graph
        val copy = org.gradle.api.tasks.AbstractCopyTask::class.java
            .getDeclaredMethod("copy")
        copy.isAccessible = true
        // output-dir creation is task-engine work, not copy() — invoking
        // the action directly must mkdir the destination itself
        jar.destinationDirectory.get().asFile.mkdirs()
        copy.invoke(jar)
        return JarFile(jar.archiveFile.get().asFile).use { jf ->
            jf.entries().asSequence().map { it.name }.toSet()
        }
    }

    private fun messages(e: Throwable): String =
        generateSequence(e) { it.cause }
            .mapNotNull { it.message }.joinToString(" | ")

    @BeforeEach
    fun resetFake() = FakeProvider.reset()

    @Test
    fun `shadow is the default and embeds combined output in the env jar`() {
        val project = project()
        val ext = minecraft(project)
        ext.sourceSet("fabric")
        ext.sourceSet("fabric").combineWith("main")
        writeOutputs(project, "main")
        writeOutputs(project, "fabric")

        val fabric = ext.sourceSet("fabric")
        // full composition and packaged view agree when every edge shadows
        assertEquals(fabric.combinedOutputs.files, fabric.packagedCombinedOutputs.files)

        val entries = jarEntries(project, "fabricJar")
        // main's classes AND resources embed flat alongside fabric's own
        assertTrue(entries.contains("com/example/main/Entry.class"), "main classes: $entries")
        assertTrue(entries.contains("main.txt"), "main resources: $entries")
        assertTrue(entries.contains("com/example/fabric/Entry.class"), "own classes: $entries")
        assertTrue(entries.contains("fabric.txt"), "own resources: $entries")
    }

    @Test
    fun `none mode excludes combined output from the packaged artifact`() {
        val project = project()
        val ext = minecraft(project)
        ext.sourceSet("fabric")
        ext.sourceSet("fabric").combineWith("main", "none")
        writeOutputs(project, "main")
        writeOutputs(project, "fabric")

        val fabric = ext.sourceSet("fabric")
        val mainOutput = sourceSets(project).getByName("main").output
        // the compile/runtime composition is mode-independent...
        assertEquals(mainOutput.files, fabric.combinedOutputs.files)
        // ...but nothing combined enters the packaged view
        assertTrue(fabric.packagedCombinedOutputs.files.isEmpty(),
            "none-mode packaged view should be empty: ${fabric.packagedCombinedOutputs.files}")

        val entries = jarEntries(project, "fabricJar")
        assertFalse(entries.contains("com/example/main/Entry.class"),
            "main classes must not ship: $entries")
        assertFalse(entries.contains("main.txt"), "main resources must not ship: $entries")
        assertTrue(entries.contains("com/example/fabric/Entry.class"), "own classes: $entries")
        assertTrue(entries.contains("fabric.txt"), "own resources: $entries")
    }

    @Test
    fun `none mode keeps classpath and source composition intact`() {
        val project = project()
        val ext = minecraft(project)
        ext.main.version.set("1.0")
        ext.sourceSet("fabric") { it.version.set("1.0") }
        ext.sourceSet("fabric").combineWith("main", "none")

        val fabricSourceSet = sourceSets(project).getByName("fabric")
        val mainClasses = sourceSets(project).getByName("main").output.classesDirs.files
        val compileFiles = project.configurations.getByName(
            fabricSourceSet.compileClasspathConfigurationName
        ).files
        // classesDirs still make the compile classpath in none mode
        assertTrue(compileFiles.containsAll(mainClasses),
            "none-mode compile classpath lost the combined output")
        // runtime carries the full output
        val runtimeFiles = project.configurations.getByName(
            fabricSourceSet.runtimeClasspathConfigurationName
        ).files
        assertTrue(runtimeFiles.containsAll(
            sourceSets(project).getByName("main").output.files))
        // combinedSourceDirs population is mode-independent (mixin-config
        // discovery is a compile-time invariant)
        val fabric = ext.sourceSet("fabric")
        assertEquals(
            sourceSets(project).getByName("main").allSource.srcDirs,
            fabric.combinedSourceDirs.files
        )
    }

    @Test
    fun `packaging mode propagates through combine chains`() {
        val project = project()
        val ext = minecraft(project)
        // chain: a -shadow-> b -none-> c — shadowing b embeds exactly
        // what b itself ships: b's own output, not c's
        ext.sourceSet("c") { it.inheritParent.set(false) }
        ext.sourceSet("b") { it.inheritParent.set(false) }
        ext.sourceSet("a") { it.inheritParent.set(false) }
        ext.sourceSet("b").combineWith("c", "none")
        ext.sourceSet("a").combineWith("b", "shadow")

        val cOutput = sourceSets(project).getByName("c").output.files
        val bOutput = sourceSets(project).getByName("b").output.files

        // a's full composition carries both (classpath invariant)
        val aFull = ext.sourceSet("a").combinedOutputs.files
        assertTrue(aFull.containsAll(bOutput), "a full composition missing b")
        assertTrue(aFull.containsAll(cOutput), "a full composition missing c")
        // a's packaged view: b's own output — c's none-edge contribution
        // stays out because shadowing embeds what the source ships
        val aPackaged = ext.sourceSet("a").packagedCombinedOutputs.files
        assertTrue(aPackaged.containsAll(bOutput),
            "a packaged should carry b's own output: $aPackaged")
        assertTrue(aPackaged.none { it in cOutput },
            "c's output leaked through b's none edge: $aPackaged")

        // flipping the chain — b shadows c, a is none on b: the none
        // edge contributes nothing at all, however deep the shadowing
        val project2 = ProjectBuilder.builder()
            .withProjectDir(dir.resolve("second").toFile())
            .withName("second")
            .build()
            .also {
                it.plugins.apply("java")
                it.plugins.apply(GradlePlugin::class.java)
            }
        val ext2 = minecraft(project2)
        ext2.sourceSet("c") { it.inheritParent.set(false) }
        ext2.sourceSet("b") { it.inheritParent.set(false) }
        ext2.sourceSet("a") { it.inheritParent.set(false) }
        ext2.sourceSet("b").combineWith("c", "shadow")
        ext2.sourceSet("a").combineWith("b", "none")
        assertTrue(ext2.sourceSet("a").packagedCombinedOutputs.files.isEmpty(),
            "none edge must contribute nothing to the package")
        // ...while b still embeds c itself
        assertTrue(ext2.sourceSet("b").packagedCombinedOutputs.files.containsAll(
            sourceSets(project2).getByName("c").output.files))
    }

    @Test
    fun `invalid packaging mode errors lazily with valid values`() {
        val project = project()
        val ext = minecraft(project)
        ext.sourceSet("fabric") { it.inheritParent.set(false) }
        // assignment never fails — validation is a point-of-use concern
        ext.sourceSet("fabric").combineWith("main", "nope")

        // the lazy path: resolving the packaged view validates
        val lazy = assertThrows(Exception::class.java) {
            ext.sourceSet("fabric").packagedCombinedOutputs.files
        }
        val lazyMsg = messages(lazy)
        assertTrue(lazyMsg.contains("\"nope\""), "names the bad value: $lazyMsg")
        assertTrue(lazyMsg.contains("shadow") && lazyMsg.contains("none") &&
                lazyMsg.contains("jarinjar"),
            "lists the valid values: $lazyMsg")

        // the post-DSL pass: project evaluation validates the final map
        val project2 = ProjectBuilder.builder()
            .withProjectDir(dir.resolve("eval").toFile())
            .withName("eval")
            .build()
            .also {
                it.plugins.apply("java")
                it.plugins.apply(GradlePlugin::class.java)
            }
        val ext2 = minecraft(project2)
        ext2.sourceSet("fabric") { it.inheritParent.set(false) }
        ext2.sourceSet("fabric").combineWith("main", "nope")
        val evalError = assertThrows(Exception::class.java) {
            evaluate(project2)
        }
        val evalMsg = messages(evalError)
        assertTrue(evalMsg.contains("\"nope\"") && evalMsg.contains("expected one of"),
            "post-DSL validation should fail with valid values: $evalMsg")
    }

    @Test
    fun `jarinjar parses but errors not yet implemented`() {
        val project = project()
        val ext = minecraft(project)
        ext.sourceSet("fabric") { it.inheritParent.set(false) }
        // a valid vocabulary value — no valid-values failure...
        ext.sourceSet("fabric").combineWith("main", "jarinjar")

        val evalError = assertThrows(Exception::class.java) {
            evaluate(project)
        }
        val msg = messages(evalError)
        assertTrue(msg.contains("not yet implemented"),
            "jarinjar must error not-implemented: $msg")
        assertFalse(msg.contains("expected one of"),
            "jarinjar is valid vocabulary, not a parse failure: $msg")
    }

    @Test
    fun `spec block carries packaging mode and explicit sections`() {
        val project = project()
        val ext = minecraft(project)
        ext.sourceSet("fabric") { it.inheritParent.set(false) }
        ext.sourceSet("fabric").combineWith("main") { spec ->
            spec.packaging = "none"
            spec.sections(EnvironmentSection.DEPENDENCIES)
        }

        // mode lands
        assertTrue(ext.sourceSet("fabric").packagedCombinedOutputs.files.isEmpty())
        // explicit sections: DEPENDENCIES wires the mod bucket extendsFrom
        assertTrue(
            project.configurations.getByName("modImplementation") in
                project.configurations.getByName("fabricModImplementation").extendsFrom,
            "spec-declared DEPENDENCIES section should wire bucket inheritance")
    }

    @Test
    fun `sourceSet positional mode overload composes in kotlin`() {
        val project = project()
        val ext = minecraft(project)
        ext.sourceSet("fabric") { it.inheritParent.set(false) }
        // the documented kotlin DSL shape — combineWith(sourceSets.main, "none")
        ext.sourceSet("fabric").combineWith(
            sourceSets(project).getByName("main"), "none")
        assertTrue(ext.sourceSet("fabric").packagedCombinedOutputs.files.isEmpty())

        // and the cross-project-shape overload on the same project
        val project2 = ProjectBuilder.builder()
            .withProjectDir(dir.resolve("xproj").toFile())
            .withName("xproj")
            .build()
            .also {
                it.plugins.apply("java")
                it.plugins.apply(GradlePlugin::class.java)
            }
        val ext2 = minecraft(project2)
        ext2.sourceSet("fabric") { it.inheritParent.set(false) }
        ext2.sourceSet("fabric").combineWith(
            project2, sourceSets(project2).getByName("main"), "none")
        assertTrue(ext2.sourceSet("fabric").packagedCombinedOutputs.files.isEmpty())
    }

    @Test
    fun `explicit shadow redeclare resets a none mode`() {
        val project = project()
        val ext = minecraft(project)
        ext.sourceSet("fabric") { it.inheritParent.set(false) }
        val fabric = ext.sourceSet("fabric")
        fabric.combineWith("main", "none")
        fabric.combineWith("main", "shadow")

        val mainOutput = sourceSets(project).getByName("main").output.files
        assertTrue(fabric.packagedCombinedOutputs.files.containsAll(mainOutput),
            "the last explicit mode wins")
    }

    @Test
    fun `groovy positional and spec forms dispatch`() {
        val project = project()
        val ext = minecraft(project)
        ext.sourceSet("fabric") { it.inheritParent.set(false) }
        val env = ext.sourceSet("fabric")

        val shell = GroovyShell()
        shell.setVariable("env", env)
        shell.setVariable("main", sourceSets(project).getByName("main"))

        // positional string form — Groovy must not coerce the mode
        // string into the EnvironmentSection vararg
        shell.evaluate("env.combineWith('main', 'none')")
        assertTrue(env.packagedCombinedOutputs.files.isEmpty(),
            "positional groovy string should set none mode")

        // spec closure form — packaging assignment hits the spec delegate
        shell.evaluate("env.combineWith('main') { packaging = 'shadow' }")
        val mainOutput = sourceSets(project).getByName("main").output.files
        assertTrue(env.packagedCombinedOutputs.files.containsAll(mainOutput),
            "groovy spec form should reset to shadow")

        // sourceSet positional form
        shell.evaluate("env.combineWith(main, 'none')")
        assertTrue(env.packagedCombinedOutputs.files.isEmpty(),
            "sourceSet positional groovy string should set none mode")
    }
}
