package xyz.wagyourtail.unimined.adapter.patchbase

import org.gradle.api.Action
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.internal.TaskInternal
import org.gradle.api.internal.project.ProjectInternal
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.jvm.tasks.Jar
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import xyz.wagyourtail.patchbase.gradle.tasks.CreateClassPatchTask
import xyz.wagyourtail.unimined.adapter.patchbase.internal.RecompileMcWiring
import xyz.wagyourtail.unimined.adapter.patchbase.task.ApplySourcePatchesTask
import xyz.wagyourtail.patchbase.gradle.tasks.CreateSourcePatchTask
import xyz.wagyourtail.unimined.adapter.patchbase.PatchBaseEnvExtension.PatchBaseCreatorSpec
import xyz.wagyourtail.unimined.minecraft.MinecraftEnvironment
import xyz.wagyourtail.unimined.minecraft.MinecraftExtension
import xyz.wagyourtail.unimined.transform.TransformExtension
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * The mache/git-style text-patch loop on an environment:
 * `patchBaseCreator()` wires `applySourcePatch`/`createSourcePatch`/
 * `createClassPatch` against the env's decompiled sources (`genSources`
 * stand-in here — a plain task with a declared output dir), and the
 * patch tasks themselves round-trip a unified-diff set against a fake
 * decompiled tree.
 */
class PatchBaseCreatorWiringTest {

    /** `Jar` with a public [copy] — `AbstractCopyTask.copy` is
     *  protected, so a test-local subclass widens it for direct
     *  invocation (same seam `RemapJarTask` uses). */
    abstract class PublicJar : Jar() {
        public override fun copy() = super.copy()
    }

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
            project.plugins.apply("xyz.wagyourtail.unimined.adapter-patchbase")
        }

    private fun evaluate(project: Project) =
        (project as ProjectInternal).evaluate()

    private fun env(project: Project): MinecraftEnvironment =
        project.extensions.getByType(MinecraftExtension::class.java).main
            .also { it.version.set("1.20.1") }

    /** Stand-in for `genSources` — registration order must not matter
     *  (the real one lands in `afterDslEval` too); the declared output
     *  dir doubles as the fake decompiled tree handle. */
    private fun stubGenSources(project: Project, outputDir: File) =
        project.tasks.register("genSources") {
            it.outputs.dir(outputDir)
        }

    @Test
    fun `patchBaseCreator registers the three loop tasks after evaluation`() {
        val project = project()
        val sourcesDir = dir.resolve("decompiled").toFile()
        stubGenSources(project, sourcesDir)
        env(project).patchBaseCreator()
        evaluate(project)

        val apply = project.tasks.getByName("applySourcePatch")
            as ApplySourcePatchesTask
        assertEquals(File(dir.toFile(), "patches"), apply.patchDir.get())
        assertEquals(File(dir.toFile(), "src/main/java"), apply.outputDir.get())
        // the decompiled-sources handle rides the task dependency on
        // the genSources stand-in's declared outputs
        assertTrue(apply.sources.get().files.contains(sourcesDir))

        val create = project.tasks.getByName("createSourcePatch")
            as CreateSourcePatchTask
        assertEquals(File(dir.toFile(), "src/main/java"), create.sourceDir.get())
        assertEquals(File(dir.toFile(), "patches"), create.outputDir.get())
        assertTrue(create.trimWhitespace.get())
        assertEquals(3, create.diffContextSize.get())

        val createClasses = project.tasks.getByName("createClassPatch")
            as CreateClassPatchTask
        assertEquals("binpatch", createClasses.patchFormat.get())
        assertEquals("patch",
            createClasses.archiveClassifier.get())
    }

    @Test
    fun `spec overrides retarget dirs and diff knobs`() {
        val project = project()
        stubGenSources(project, dir.resolve("decompiled").toFile())
        env(project).patchBaseCreator(Action<PatchBaseCreatorSpec> { spec ->
            spec.patchDir.fileValue(dir.resolve("mc-patches").toFile())
            spec.sourceDir.fileValue(dir.resolve("work").toFile())
            spec.diffContextSize.set(5)
            spec.trimWhitespace.set(false)
            spec.patchFormat.set("bdiff")
        })
        evaluate(project)

        val apply = project.tasks.getByName("applySourcePatch")
            as ApplySourcePatchesTask
        assertEquals(dir.resolve("mc-patches").toFile(), apply.patchDir.get())
        assertEquals(dir.resolve("work").toFile(), apply.outputDir.get())
        val create = project.tasks.getByName("createSourcePatch")
            as CreateSourcePatchTask
        assertFalse(create.trimWhitespace.get())
        assertEquals(5, create.diffContextSize.get())
        assertEquals("bdiff",
            (project.tasks.getByName("createClassPatch")
                as CreateClassPatchTask).patchFormat.get())
    }

    @Test
    fun `non-main env gets env-infix tasks and per-env dirs`() {
        val project = project()
        project.tasks.register("genClientSources") {
            it.outputs.dir(dir.resolve("decompiled-client").toFile())
        }
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        ext.sourceSet("client") { env ->
            env.version.set("1.20.1")
            env.patchBaseCreator()
        }
        evaluate(project)

        val apply = project.tasks.getByName("applyClientSourcePatch")
            as ApplySourcePatchesTask
        assertEquals(File(dir.toFile(), "patches/client"), apply.patchDir.get())
        assertEquals(File(dir.toFile(), "src/client/java"), apply.outputDir.get())
        assertEquals("patch-client",
            (project.tasks.getByName("createClientClassPatch")
                as CreateClassPatchTask).archiveClassifier.get())
        // pre-convention env-suffix names stay as deprecated aliases
        // pointing at the canonical tasks
        val aliases = mapOf(
            "applySourcePatchClient" to "applyClientSourcePatch",
            "createSourcePatchClient" to "createClientSourcePatch",
            "createClassPatchClient" to "createClientClassPatch",
        )
        for ((alias, canonical) in aliases) {
            val a = project.tasks.getByName(alias)
            assertTrue(a.dependsOn.any {
                it.toString().contains(canonical)
            }, "$alias should depend on $canonical")
        }
    }

    @Test
    fun `missing genSources fails with guidance`() {
        val project = project()
        env(project).patchBaseCreator()
        val ex = assertThrows(Exception::class.java) { evaluate(project) }
        val chain = generateSequence(ex as Throwable) { it.cause }
        assertTrue(chain.any { it.message?.contains("genSources") == true },
            "expected a genSources hint, got: $ex")
    }

    @Test
    fun `loop tasks skip cleanly until their dirs exist`() {
        val project = project()
        stubGenSources(project, dir.resolve("decompiled").toFile())
        env(project).patchBaseCreator()
        evaluate(project)

        fun satisfied(task: Task): Boolean =
            (task as TaskInternal).getOnlyIf().isSatisfiedBy(task)

        val apply = project.tasks.getByName("applySourcePatch")
        val create = project.tasks.getByName("createSourcePatch")
        // fresh checkout: no patches/ and no patched overlay yet
        assertFalse(satisfied(apply))
        assertFalse(satisfied(create))

        dir.resolve("patches").createDirectories()
        dir.resolve("src/main/java").createDirectories()
        assertTrue(satisfied(apply))
        assertTrue(satisfied(create))
    }

    @Test
    fun `consume side lands deps on the env config and the jar processor`() {
        val project = project()
        env(project).patchBase("com.example:my-patches:1.0@zip")

        val deps = project.configurations.getByName("patchBaseMain")
            .dependencies
        assertEquals(1, deps.size)
        assertEquals("com.example:my-patches:1.0",
            deps.single().run { "$group:$name:$version" })

        val spec = project.extensions.getByType(TransformExtension::class.java)
            .sourceSet("main")
        assertTrue(spec.jarProcessors.any { it is PatchBaseProcessor })
        assertNotNull(spec.envArtifactBindings["patchbase"])
    }

    @Test
    fun `minecraft-level patchBase verbs alias the main env`() {
        val project = project()
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        ext.main.version.set("1.20.1")
        // the Groovy-resolution hook: `minecraft { patchBase(...) }`
        // dispatches through this extension on MinecraftExtension
        assertNotNull((ext as org.gradle.api.plugins.ExtensionAware)
            .extensions.findByName("patchBase"))
        ext.patchBase("com.example:ext-patches:2.0")

        assertEquals("com.example:ext-patches:2.0",
            project.configurations.getByName("patchBaseMain")
                .dependencies.single()
                .run { "$group:$name:$version" })
    }

    /**
     * The loop, end to end, on real task instances: a fake decompiled
     * tree (`base/`) + an edit tree (`src/`) → `createSourcePatch`
     * materializes `patches/` → `applySourcePatch` reproduces `src/`
     * from `patches/`+`base/` → `create`→`apply` again round-trips
     * identical content. `trimWhitespace=false` keeps the fixture's
     * indentation so re-application is byte-stable.
     */
    @Test
    fun `apply and create round-trip a patch set`() {
        val project = project()

        val base = dir.resolve("base")
        val src = dir.resolve("work")
        val patches = dir.resolve("patches")
        base.resolve("com/example").createDirectories()
        src.resolve("com/example").createDirectories()
        // the edit touches the closing brace — the hunk's context
        // reaches the last line, the case patchbase:2.0.0's own apply
        // task cannot survive (phantom trailing-line parse bug the
        // local ApplySourcePatchesTask works around)
        base.resolve("com/example/Foo.java").writeText(
            "package com.example;\n\n" +
                "public class Foo {\n" +
                "    public int value() {\n" +
                "        return 1;\n" +
                "    }\n" +
                "}\n")
        src.resolve("com/example/Foo.java").writeText(
            "package com.example;\n\n" +
                "public class Foo {\n" +
                "    public int value() {\n" +
                "        return 2; // patched\n" +
                "    }\n\n" +
                "    public int added() {\n" +
                "        return 9;\n" +
                "    }\n" +
                "}\n")
        // net-new file — travels verbatim, not as a diff
        src.resolve("com/example/New.java").writeText(
            "package com.example;\n\npublic class New {}\n")

        fun createPatch(source: File, out: File): CreateSourcePatchTask =
            project.tasks.register(
                "create-${out.name}-${System.nanoTime()}",
                CreateSourcePatchTask::class.java
            ).get().apply {
                sources.set(project.files(base.toFile()))
                trimWhitespace.set(false)
                diffContextSize.set(3)
                sourceDir.set(source)
                outputDir.set(out)
                run()
            }

        fun applyPatch(patchSet: File, out: File): ApplySourcePatchesTask =
            project.tasks.register(
                "apply-${out.name}-${System.nanoTime()}",
                ApplySourcePatchesTask::class.java
            ).get().apply {
                sources.set(project.files(base.toFile()))
                patchDir.set(patchSet)
                outputDir.set(out)
                apply()
            }

        // create: edited tree -> patch dir
        createPatch(src.toFile(), patches.toFile())
        val fooPatch = patches.resolve("com/example/Foo.java.patch")
        assertTrue(fooPatch.toFile().exists(),
            "edited file produces a .patch")
        assertTrue(fooPatch.readText().contains("return 2"))
        assertTrue(patches.resolve("com/example/New.java").toFile().exists(),
            "net-new file copies verbatim")

        // apply: patch dir + decompiled base -> patched tree
        val applied = dir.resolve("applied")
        applyPatch(patches.toFile(), applied.toFile())
        val appliedFoo = applied.resolve("com/example/Foo.java")
        assertTrue(appliedFoo.readText().contains("return 2"))
        assertTrue(appliedFoo.readText().contains("public int added"))
        assertTrue(applied.resolve("com/example/New.java").readText()
            .contains("public class New"))

        // round trip: diff the applied tree again, re-apply, identical
        val patches2 = dir.resolve("patches2")
        createPatch(applied.toFile(), patches2.toFile())
        val applied2 = dir.resolve("applied2")
        applyPatch(patches2.toFile(), applied2.toFile())
        assertEquals(appliedFoo.readText(),
            applied2.resolve("com/example/Foo.java").readText())
    }

    /**
     * `recompileMc`/`recompileMcClasses` — the FG-era `recompMc`
     * analog: a dedicated javac pass over the patched overlay (dev
     * jar + libraries on the classpath) whose classes merge over the
     * dev jar. Skips until the overlay exists; non-java overlay files
     * never reach javac.
     */
    @Test
    fun `patchBaseCreator registers the recompile tasks after evaluation`() {
        val project = project()
        stubGenSources(project, dir.resolve("decompiled").toFile())
        env(project).patchBaseCreator()
        evaluate(project)

        val compile = project.tasks.getByName("recompileMcClasses")
            as JavaCompile
        assertEquals("patchbase", compile.group)
        assertTrue(compile.destinationDirectory.get().asFile.path
            .replace('\\', '/')
            .endsWith("unimined/recompileMc/classes"))

        val recompile = project.tasks.getByName("recompileMc")
            as Jar
        assertEquals("patchbase", recompile.group)
        assertEquals("recompiled", recompile.archiveClassifier.get())
        assertEquals("minecraft-main-1.20.1",
            recompile.archiveBaseName.get())
        assertEquals("minecraft-main-1.20.1-recompiled.jar",
            recompile.archiveFileName.get())

        fun satisfied(task: Task): Boolean =
            (task as TaskInternal).getOnlyIf().isSatisfiedBy(task)
        // no overlay yet — recompile has nothing to substitute in
        assertFalse(satisfied(recompile))

        dir.resolve("src/main/java/com/example").createDirectories()
        dir.resolve("src/main/java/com/example/Foo.java")
            .writeText("package com.example;\n\npublic class Foo {}\n")
        dir.resolve("src/main/java/config.json").writeText("{}")
        assertTrue(satisfied(recompile))
        assertEquals(
            setOf(dir.resolve("src/main/java/com/example/Foo.java")
                .toFile()),
            compile.source.files,
            "javac consumes only the overlay's .java files")
    }

    @Test
    fun `non-main env gets env-infix recompile tasks`() {
        val project = project()
        project.tasks.register("genClientSources") {
            it.outputs.dir(dir.resolve("decompiled-client").toFile())
        }
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        ext.sourceSet("client") { env ->
            env.version.set("1.20.1")
            env.patchBaseCreator()
        }
        evaluate(project)

        assertNotNull(project.tasks.findByName("recompileClientMc"))
        assertNotNull(project.tasks.findByName("recompileClientMcClasses"))
        // pre-convention env-suffix names stay as deprecated aliases
        assertNotNull(project.tasks.findByName("recompileMcClient"))
        assertNotNull(project.tasks.findByName("recompileMcClientClasses"))
    }

    /**
     * The merge semantics of `recompileMc` on a real `Jar` instance
     * (the dev jar can't resolve in a unit test, so the base is a
     * fixture jar): recompiled classes replace the stock entries,
     * verbatim resource patches land, unpatched base content carries
     * through, and jar signatures strip — the stale-digest case that
     * breaks a naively overlaid signed jar.
     */
    @Test
    fun `recompiled jar merges overlay classes over the base jar`() {
        val project = project()

        val base = dir.resolve("base.jar")
        JarOutputStream(Files.newOutputStream(base)).use { jar ->
            mapOf(
                "META-INF/MANIFEST.MF" to
                    "Manifest-Version: 1.0\r\nX-Base: yes\r\n\r\n",
                "META-INF/MOJANG_C.SF" to "signature",
                "a/Base.class" to "base-class",
                "data.txt" to "base-data",
            ).forEach { (name, data) ->
                jar.putNextEntry(JarEntry(name))
                jar.write(data.toByteArray())
                jar.closeEntry()
            }
        }
        val classes = dir.resolve("classes")
        classes.resolve("a").createDirectories()
        classes.resolve("a/Base.class").writeText("patched-class")
        classes.resolve("a/Added.class").writeText("added-class")
        val overlay = dir.resolve("overlay")
        overlay.resolve("x").createDirectories()
        overlay.resolve("config.json").writeText("{}")
        overlay.resolve("x/Added.java").writeText("// not copied")

        val task = project.tasks.register("recompileMcTest",
            PublicJar::class.java).get()
        task.destinationDirectory.set(dir.toFile())
        task.archiveFileName.set("recompiled.jar")
        RecompileMcWiring.overlayCopy(
            task,
            classes = project.files(classes.toFile()),
            overlay = project.files(overlay.toFile()),
            // same provider→zipTree shape the wiring feeds it —
            // archive contents unpack lazily at visit time
            baseTree = project.zipTree(
                project.provider { base.toFile() }),
        )
        task.copy()

        JarFile(dir.resolve("recompiled.jar").toFile()).use { jar ->
            fun entry(name: String) = jar.getEntry(name)
                ?.let { jar.getInputStream(it).readBytes() }
            assertArrayEquals(
                "patched-class".toByteArray(), entry("a/Base.class"),
                "recompiled class replaces the base entry")
            assertArrayEquals(
                "added-class".toByteArray(), entry("a/Added.class"),
                "net-new classes land")
            assertArrayEquals(
                "base-data".toByteArray(), entry("data.txt"),
                "unpatched base entries carry through")
            assertEquals("{}", entry("config.json")?.decodeToString(),
                "verbatim resource patches land")
            assertNull(entry("x/Added.java"),
                ".java sources never ship")
            assertNull(entry("META-INF/MOJANG_C.SF"),
                "signature files strip")
            assertNull(jar.manifest.mainAttributes.getValue("X-Base"),
                "base manifest does not survive — the task writes " +
                    "its own")
        }
    }

}
