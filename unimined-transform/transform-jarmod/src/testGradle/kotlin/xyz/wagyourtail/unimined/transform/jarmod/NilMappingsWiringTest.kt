package xyz.wagyourtail.unimined.transform.jarmod

import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import xyz.wagyourtail.unimined.minecraft.MinecraftExtension
import xyz.wagyourtail.unimined.remap.ModArtifactsStepSpec
import xyz.wagyourtail.unimined.remap.RemapExtension
import xyz.wagyourtail.unimined.remapper.uml.nil.NilMappingsScanTask
import org.gradle.jvm.tasks.Jar

/**
 * Nil mappings wiring: scan task registration, jar copy-spec injection
 * (the file must ride into both the plain jar and `remapJar`'s mirrored
 * spec), and the ldc-rewrite opt-out convention that keeps nilmod
 * string literals in the compile namespace.
 */
class NilMappingsWiringTest {

    private fun wiredProject(): Pair<org.gradle.api.Project, xyz.wagyourtail.unimined.minecraft.MinecraftEnvironment> {
        val project = ProjectBuilder.builder().build()
        project.pluginManager.apply("java")
        project.plugins.apply(
            xyz.wagyourtail.unimined.minecraft.GradlePlugin::class.java)
        project.plugins.apply(
            xyz.wagyourtail.unimined.transform.GradlePlugin::class.java)
        project.plugins.apply(GradlePlugin::class.java)
        project.plugins.apply(
            xyz.wagyourtail.unimined.remap.GradlePlugin::class.java)
        val env = project.extensions
            .getByType(MinecraftExtension::class.java)
            .environments.maybeCreate("main")
        env.inheritParent.set(false)
        env.version.set("1.7.10")
        // Exercise the real loader verb → descriptor → wiringVariant
        // dispatch path; wiring must not be invoked directly here.
        env.nil("1.3.6")
        return project to env
    }

    @Test
    fun `ldc opt-out convention is contributed`() {
        val (project, _) = wiredProject()
        // real dispatch is post-DSL, as in a consuming build
        (project as org.gradle.api.internal.project.ProjectInternal).evaluate()
        val remap = project.extensions.getByType(RemapExtension::class.java)
        val conventions = remap.sourceSet("main").modArtifactsConventions
        assertTrue(conventions.isNotEmpty(), "no modArtifacts conventions contributed")
        val spec = ModArtifactsStepSpec()
        conventions.forEach { it.execute(spec) }
        assertFalse(spec.remapClassNameLdcs,
            "nil wiring must disable class-name ldc rewriting")
    }

    @Test
    fun `scan task registers and injects into jar`() {
        val (project, _) = wiredProject()
        // fire afterDslEval hooks
        (project as org.gradle.api.internal.project.ProjectInternal).evaluate()
        val scan = project.tasks.findByName("scanNilMappings")
        assertNotNull(scan, "scanNilMappings not registered")
        assertTrue(scan is NilMappingsScanTask, "wrong task type: ${scan!!::class.java}")
        scan as NilMappingsScanTask
        assertEquals("mappings.json", scan.outputFile.get().asFile.name)
        assertTrue(scan.outputFile.get().asFile.path.replace('\\', '/')
            .contains("nilMappings/main/mappings.json"),
            "unexpected output path: ${scan.outputFile.get().asFile}")
        val jar = project.tasks.findByName("jar") as? Jar
        assertNotNull(jar, "no jar task")
        // the injected `from(scanTask.outputFile)` must wire the task
        // dependency so the scan runs before the jar assembles — this is
        // what carries the file into remapJar's mirrored spec too
        val deps = jar!!.taskDependencies.getDependencies(jar).map { it.name }
        assertTrue("scanNilMappings" in deps,
            "jar does not depend on the scan task; deps: $deps")
    }

    @Test
    fun `single logical mods group contributes classes to scan inputs`() {
        val (project, env) = wiredProject()
        val sourceSets = project.extensions.getByType(
            org.gradle.api.tasks.SourceSetContainer::class.java)
        val shared = sourceSets.create("shared")
        env.mods(org.gradle.api.Action { groups ->
            groups.maybeCreate("testmod").sourceSet(shared)
        })
        (project as org.gradle.api.internal.project.ProjectInternal).evaluate()
        val scan = project.tasks.getByName("scanNilMappings") as NilMappingsScanTask
        val classDirs = scan.classDirs.files.map { it.absolutePath.replace('\\', '/') }
        assertTrue(classDirs.any { it.contains("classes/java/shared") ||
            it.contains("classes/kotlin/shared") },
            "same-mod group source-set output missing from scan dirs: $classDirs")
    }

}
