package xyz.wagyourtail.unimined.transform

import org.gradle.api.artifacts.type.ArtifactTypeDefinition
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import xyz.wagyourtail.unimined.minecraft.MinecraftExtension
import java.nio.file.Path

/**
 * Verifies the environment↔source-set classpath wiring: stub bucket + raw/
 * dev views, library bucket extension, remapped mod views, bundle tasks,
 * and late-binding replay when the transform plugin applies after `main`
 * is already bound.
 */
class EnvironmentClasspathWiringTest {

    @TempDir
    lateinit var dir: Path

    private fun project(applyOrder: List<String>) = ProjectBuilder.builder()
        .withProjectDir(dir.toFile())
        .build()
        .also { project ->
            project.plugins.apply("java")
            project.plugins.apply("xyz.wagyourtail.unimined.remap")
            project.plugins.apply("xyz.wagyourtail.unimined.minecraft")
            for (id in applyOrder) project.plugins.apply(id)
        }

    @Test
    fun `main environment wiring creates stub bucket, views, and bundle inputs`() {
        val project = project(listOf("xyz.wagyourtail.unimined.transform"))

        val minecraft = project.configurations.getByName("minecraft")
        assertEquals(false, minecraft.isCanBeResolved)
        assertEquals(false, minecraft.isCanBeConsumed)

        val libraries = project.configurations.getByName("minecraftLibraries")
        assertEquals(true, libraries.isCanBeResolved)

        val raw = project.configurations.getByName("minecraftRaw")
        assertEquals(true, raw.isCanBeResolved)
        assertEquals(listOf(minecraft), raw.extendsFrom.toList())
        assertEquals(
            "unimined-mc-raw-main",
            raw.attributes.getAttribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE)
        )

        val dev = project.configurations.getByName("minecraftDev")
        assertEquals(listOf(minecraft), dev.extendsFrom.toList())
        assertEquals(
            "unimined-mc-dev-main",
            dev.attributes.getAttribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE)
        )

        for (modView in listOf("modImplementationRemapped", "modRuntimeOnlyRemapped")) {
            val view = project.configurations.getByName(modView)
            assertEquals(
                "unimined-remapped-main-${modView.removeSuffix("Remapped")}",
                view.attributes.getAttribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE)
            )
        }
        assertEquals(
            listOf(project.configurations.getByName("modImplementation")),
            project.configurations.getByName("modImplementationRemapped").extendsFrom.toList()
        )

        // Transform inputs resolve the env's declared mapping sources
        // inside the bundle provider — artifact transforms cannot
        // depend on a bundle-producer task, so no produceMappingBundle_*
        // task is registered by this wiring and no eager mappings
        // configuration exists.

        assertTrue(
            project.configurations.getByName("compileClasspath")
                .extendsFrom.contains(libraries)
        )
        assertTrue(
            project.configurations.getByName("runtimeClasspath")
                .extendsFrom.contains(libraries)
        )

        assertNotNull(
            project.repositories.findByName("uniminedGenerated")
        )
    }

    @Test
    fun `dev view is registered even when transform applies after main binds`() {
        // java + minecraft first: `main` binds before transform's handler
        // registers — onEnvironmentBound must replay bound envs
        val project = ProjectBuilder.builder().withProjectDir(dir.toFile()).build()
        project.plugins.apply("java")
        project.plugins.apply("xyz.wagyourtail.unimined.minecraft")
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        ext.version.set("1.20.1")

        assertTrue(project.configurations.findByName("minecraftDev") == null)
        project.plugins.apply("xyz.wagyourtail.unimined.remap")
        project.plugins.apply("xyz.wagyourtail.unimined.transform")
        assertNotNull(project.configurations.findByName("minecraftDev"))
    }

    @Test
    fun `named source set environments get their own wiring`() {
        val project = ProjectBuilder.builder().withProjectDir(dir.toFile()).build()
        project.plugins.apply("java")
        project.plugins.apply("xyz.wagyourtail.unimined.remap")
        project.plugins.apply("xyz.wagyourtail.unimined.minecraft")
        project.plugins.apply("xyz.wagyourtail.unimined.transform")
        project.extensions.getByType(
            org.gradle.api.tasks.SourceSetContainer::class.java
        ).maybeCreate("fabric")
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        ext.sourceSet("fabric") { it.version.set("1.20.1") }

        assertNotNull(project.configurations.findByName("fabricMinecraft"))
        assertNotNull(project.configurations.findByName("fabricMinecraftLibraries"))
        assertNotNull(project.configurations.findByName("fabricMinecraftRaw"))
        assertNotNull(project.configurations.findByName("fabricMinecraftDev"))
        assertNotNull(project.configurations.findByName("fabricModImplementationRemapped"))
        assertEquals(
            "unimined-mc-dev-fabric",
            project.configurations.getByName("fabricMinecraftDev")
                .attributes.getAttribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE)
        )
    }

    @Test
    fun `loaderless env wires access apply and env-local generated doc`() {
        val project = project(listOf("xyz.wagyourtail.unimined.transform"))
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        val env = ext.main
        env.devNamespace.set("named")
        env.access { it.accessible("a/B") }
        (project as org.gradle.api.internal.project.ProjectInternal).evaluate()

        // generated doc is env-local — not in the combine-propagated
        // collections (children re-emit inherited rules themselves)
        assertTrue(env.generatedAccessFiles.from.isNotEmpty())
        assertTrue(
            env.accessWideners.from.none { it is org.gradle.api.provider.Provider<*> }
        )

        // no loader family claims the apply step — the universal
        // wiring contributes two JAR-surface classTweaker applies:
        // the own-declarations spec feeding all three collections
        // (headerless ATs bound to the dev ns) plus a dep-declared
        // spec, and the `remap-access` step for dep-jar embedded docs
        val remap = project.extensions.getByType(
            xyz.wagyourtail.unimined.remap.RemapExtension::class.java)
        val steps = remap.sourceSet("main")
            .contributedSteps(xyz.wagyourtail.unimined.remap.spi.RemapSurface.JAR)
        val applySpecs = steps.filterIsInstance<
                xyz.wagyourtail.unimined.remapper.access
                        .ApplyClassTweakerStepSpec>()
        assertEquals(2, applySpecs.size)
        val ownSpec = applySpecs.single { spec ->
            spec.classTweakerFiles.from.containsAll(
                listOf(env.accessWideners, env.accessTransformers,
                    env.generatedAccessFiles))
        }
        assertEquals("named",
            ownSpec.headerlessNamespace.get())
        assertTrue(steps.any {
            it is xyz.wagyourtail.unimined.remap.AccessRemapStepSpec })

        // canonical doc emits classTweaker (self-describing, all rule
        // kinds) under the jar-layout dir — the env's own jar embeds
        // it for dependents to discover via the dep-access scan
        val gen = env.generatedAccessFiles.files.single()
        assertEquals("main-access.classtweaker", gen.name)
        assertTrue(gen.readText()
            .startsWith("classTweaker\tv1\tnamed"))
        assertTrue(gen.path.replace('\\', '/')
            .contains("unimined/access/main/jar/"))
    }

    @Test
    fun `loaderless access doc honors format and fileName`() {
        val project = project(listOf("xyz.wagyourtail.unimined.transform"))
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        val env = ext.main
        env.devNamespace.set("named")
        env.access {
            it.format = "at"
            it.fileName = "META-INF/custom_at.cfg"
            it.accessible("a/B")
        }
        (project as org.gradle.api.internal.project.ProjectInternal).evaluate()

        val gen = env.generatedAccessFiles.files.single()
        assertEquals("META-INF/custom_at.cfg",
            gen.relativeTo(
                dir.resolve("build/unimined/access/main/jar").toFile()
            ).path.replace('\\', '/'))
        assertEquals("public a/B", gen.readText().trim())
    }

    @Test
    fun `dep access scan buckets headered, headerless, and II docs`() {
        // fixture dep jar: conventional AW + META-INF AT + fmj
        // accessWidener field + loom injected_interfaces
        val jar = dir.resolve("dep-1.0.jar")
        java.util.jar.JarOutputStream(
            java.io.FileOutputStream(jar.toFile()),
            java.util.jar.Manifest().apply {
                mainAttributes.putValue("Manifest-Version", "1.0")
                mainAttributes.putValue("FMLAT", "custom/at_file.cfg")
            }).use { out ->
            fun put(name: String, content: String) {
                out.putNextEntry(java.util.jar.JarEntry(name))
                out.write(content.toByteArray())
                out.closeEntry()
            }
            put("modid.accesswidener",
                "accessWidener\tv2\tintermediary\n" +
                        "transitive-accessible\tclass\ta/B\n")
            put("META-INF/accesstransformer.cfg",
                "public a.B\n")
            put("custom/at_file.cfg", "public a.B\n")
            put("fabric.mod.json", """{"id":"dep",
                "accessWidener":"odd/name.txt",
                "custom":{"loom:injected_interfaces":
                    {"net/mc/A":["com/x/II"]}}}""")
            put("odd/name.txt",
                "accessWidener\tv2\tintermediary\n" +
                        "transitive-accessible\tclass\ta/C\n")
        }
        val warnings = mutableListOf<String>()
        val result = xyz.wagyourtail.unimined.transform.internal
            .DepAccessScan.scan(
                jar, dir.resolve("cache"), "intermediary", warnings::add)

        // AW extension + fmj-referenced doc → dep channel
        assertEquals(3, result.declared.size)
        assertTrue(result.declared.any {
            it.fileName.toString().contains("modid.accesswidener") })
        assertTrue(result.declared.any {
            it.fileName.toString().contains("name.txt") })
        // synthesized II doc carries transitive inject-interface rows
        val ii = result.declared.single {
            it.fileName.toString().contains("injected-interfaces") }
        assertTrue(ii.toFile().readText().contains(
            "transitive-inject-interface\tnet/mc/A\tcom/x/II"))
        // conventional + FMLAT-listed ATs → unconditional channel
        assertEquals(2, result.ats.size)

        // without a dep ns, headerless docs are skipped with a warn
        val noNs = xyz.wagyourtail.unimined.transform.internal
            .DepAccessScan.scan(
                jar, dir.resolve("cache2"), null, warnings::add)
        assertEquals(0, noNs.ats.size)
        assertTrue(warnings.any { it.contains("accesstransformer.cfg") })
    }

    @Test
    fun `default production wiring swaps jar classifier and registers remapJar`() {
        val project = project(listOf("xyz.wagyourtail.unimined.transform"))
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        ext.sourceSet("main") {
            it.version.set("1.20.1")
            it.devNamespace.set("named")
            it.modSourceNamespaces.set(listOf("intermediary"))
        }
        (project as org.gradle.api.internal.project.ProjectInternal).evaluate()

        val jar = project.tasks.getByName("jar") as org.gradle.jvm.tasks.Jar
        assertEquals("dev", jar.archiveClassifier.get())

        val remapJar = project.tasks.getByName("remapJar")
                as xyz.wagyourtail.unimined.remap.task.RemapJarTask
        // prod artifact claims the jar's original unclassified name —
        // i.e. the dev jar's name with the -dev suffix removed
        assertEquals(
            jar.archiveFileName.get().replace("-dev.", "."),
            remapJar.archiveFile.get().asFile.name
        )
        assertTrue(
            (remapJar as org.gradle.api.internal.TaskInternal)
                .onlyIf.isSatisfiedBy(remapJar)
        )
        val buildDeps = project.tasks.getByName("build")
            .taskDependencies.getDependencies(null)
        assertTrue(buildDeps.any { it.name == "remapJar" })
    }

    @Test
    fun `identity environment keeps the original jar and skips remapJar`() {
        val project = project(listOf("xyz.wagyourtail.unimined.transform"))
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        ext.sourceSet("main") {
            it.version.set("1.20.1")
            it.devNamespace.set("named")
            it.modSourceNamespaces.set(listOf("named"))
        }
        (project as org.gradle.api.internal.project.ProjectInternal).evaluate()

        // one artifact serves dev and production — no -dev split
        val jar = project.tasks.getByName("jar") as org.gradle.jvm.tasks.Jar
        assertEquals("", jar.archiveClassifier.get())

        // remapJar stays registered (stable task name) but skips itself
        val remapJar = project.tasks.getByName("remapJar")
        org.junit.jupiter.api.Assertions.assertFalse(
            (remapJar as org.gradle.api.internal.TaskInternal)
                .onlyIf.isSatisfiedBy(remapJar)
        )
    }

    @Test
    fun `isIdentityRemap is true only when the single prod namespace equals dev`() {
        val project = project(listOf("xyz.wagyourtail.unimined.transform"))
        val ext = project.extensions.getByType(MinecraftExtension::class.java)

        ext.sourceSet("main") {
            it.version.set("1.20.1")
            it.devNamespace.set("named")
            it.modSourceNamespaces.set(listOf("named"))
        }
        ext.sourceSet("client") {
            it.version.set("1.20.1")
            it.devNamespace.set("named")
            it.modSourceNamespaces.set(listOf("intermediary"))
        }
        ext.sourceSet("server") {
            it.version.set("1.20.1")
            it.devNamespace.set("named")
            it.modSourceNamespaces.set(listOf("named", "intermediary"))
        }

        assertTrue(ext.isIdentityRemap("main").get())
        org.junit.jupiter.api.Assertions.assertFalse(
            ext.isIdentityRemap("client").get()
        )
        // mixed prod namespaces are not identity — they error elsewhere
        // on the single-namespace requirement
        org.junit.jupiter.api.Assertions.assertFalse(
            ext.isIdentityRemap("server").get()
        )
    }

    @Test
    fun `defaultRemapJar false skips production wiring`() {
        val project = project(listOf("xyz.wagyourtail.unimined.transform"))
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        ext.sourceSet("main") {
            it.version.set("1.20.1")
            it.defaultRemapJar.set(false)
        }
        (project as org.gradle.api.internal.project.ProjectInternal).evaluate()

        val jar = project.tasks.getByName("jar") as org.gradle.jvm.tasks.Jar
        assertEquals("", jar.archiveClassifier.get())
        assertTrue(project.tasks.findByName("remapJar") == null)
    }

    @Test
    fun `user archive classifier gains a dev suffix for the dev jar`() {
        val project = project(listOf("xyz.wagyourtail.unimined.transform"))
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        ext.sourceSet("main") {
            it.version.set("1.20.1")
            it.devNamespace.set("named")
            it.modSourceNamespaces.set(listOf("intermediary"))
        }
        project.tasks.named("jar", org.gradle.jvm.tasks.Jar::class.java) {
            it.archiveClassifier.set("shadow")
        }
        (project as org.gradle.api.internal.project.ProjectInternal).evaluate()

        val jar = project.tasks.getByName("jar") as org.gradle.jvm.tasks.Jar
        assertEquals("shadow-dev", jar.archiveClassifier.get())
        val remapJar = project.tasks.getByName("remapJar")
                as xyz.wagyourtail.unimined.remap.task.RemapJarTask
        assertTrue(remapJar.archiveFile.get().asFile.name.endsWith("-shadow.jar"))
    }

}
