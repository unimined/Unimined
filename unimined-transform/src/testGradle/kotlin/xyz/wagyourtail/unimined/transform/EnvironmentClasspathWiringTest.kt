package xyz.wagyourtail.unimined.transform

import org.gradle.api.Project
import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.type.ArtifactTypeDefinition
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import xyz.wagyourtail.unimined.api.spec.ProductSpec
import xyz.wagyourtail.unimined.mapping.MappingTree
import xyz.wagyourtail.unimined.minecraft.MinecraftExtension
import xyz.wagyourtail.unimined.minecraft.spi.ArtifactKey
import xyz.wagyourtail.unimined.minecraft.spi.AssetIndex
import xyz.wagyourtail.unimined.minecraft.spi.EnvironmentCapabilities
import xyz.wagyourtail.unimined.minecraft.spi.LaunchMetadata
import xyz.wagyourtail.unimined.minecraft.spi.NamespaceMode
import xyz.wagyourtail.unimined.minecraft.spi.ProductArtifacts
import xyz.wagyourtail.unimined.minecraft.spi.ProductProvider
import xyz.wagyourtail.unimined.minecraft.spi.ProviderContext
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

        for (modView in listOf(
            "modImplementationRemapped", "modCompileOnlyRemapped", "modRuntimeOnlyRemapped"
        )) {
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
    fun `mod sources views request docs variants and populate the dev-sources map`() {
        val project = project(listOf("xyz.wagyourtail.unimined.transform"))
        val ext = project.extensions.getByType(MinecraftExtension::class.java)

        for (bucket in listOf("modImplementation", "modCompileOnly", "modRuntimeOnly")) {
            val remapped = project.configurations.getByName("${bucket}SourcesRemapped")
            assertTrue(remapped.isCanBeResolved)
            assertFalse(remapped.isCanBeConsumed)
            assertEquals(
                "unimined-remapped-sources-main-$bucket",
                remapped.attributes.getAttribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE)
            )
            assertEquals(
                "documentation",
                remapped.attributes.getAttribute(
                    org.gradle.api.attributes.Category.CATEGORY_ATTRIBUTE)?.name
            )
            assertEquals(
                "sources",
                remapped.attributes.getAttribute(
                    org.gradle.api.attributes.DocsType.DOCS_TYPE_ATTRIBUTE)?.name
            )
            assertEquals(
                listOf(project.configurations.getByName(bucket)),
                remapped.extendsFrom.toList()
            )

            val direct = project.configurations.getByName("${bucket}SourcesDirect")
            assertEquals(
                ArtifactTypeDefinition.JAR_TYPE,
                direct.attributes.getAttribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE)
            )
            assertEquals(
                "sources",
                direct.attributes.getAttribute(
                    org.gradle.api.attributes.DocsType.DOCS_TYPE_ATTRIBUTE)?.name
            )
        }

        assertEquals(
            project.configurations.getByName("modImplementationSourcesRemapped"),
            ext.modDevSourcesViewConfigurations["main:modImplementation"]
        )
        assertEquals(
            project.configurations.getByName("modCompileOnlySourcesRemapped"),
            ext.modDevSourcesViewConfigurations["main:modCompileOnly"]
        )
        assertEquals(
            project.configurations.getByName("modRuntimeOnlySourcesRemapped"),
            ext.modDevSourcesViewConfigurations["main:modRuntimeOnly"]
        )
    }

    @Test
    fun `modCompileOnly bucket is declarable-only with direct and remapped views`() {
        val project = project(listOf("xyz.wagyourtail.unimined.transform"))
        val ext = project.extensions.getByType(MinecraftExtension::class.java)

        val bucket = project.configurations.getByName("modCompileOnly")
        assertFalse(bucket.isCanBeResolved)
        assertFalse(bucket.isCanBeConsumed)

        val direct = project.configurations.getByName("modCompileOnlyDirect")
        assertTrue(direct.isCanBeResolved)
        assertEquals(listOf(bucket), direct.extendsFrom.toList())

        assertEquals(
            project.configurations.getByName("modCompileOnlyRemapped"),
            ext.modDevViewConfigurations["main:modCompileOnly"]
        )
    }

    @Test
    fun `direct sources view resolves a dep's published -sources jar`() {
        val project = project(listOf("xyz.wagyourtail.unimined.transform"))
        val module = dir.resolve("repo/com/example/mod/1.0").toFile()
        module.mkdirs()
        val stub = byteArrayOf(0x50, 0x4B, 0x05, 0x06, *ByteArray(18))
        module.resolve("mod-1.0.jar").writeBytes(stub)
        module.resolve("mod-1.0-sources.jar").writeBytes(stub)
        module.resolve("mod-1.0.pom").writeText(
            "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">" +
                    "<modelVersion>4.0.0</modelVersion>" +
                    "<groupId>com.example</groupId>" +
                    "<artifactId>mod</artifactId>" +
                    "<version>1.0</version>" +
                    "<packaging>jar</packaging></project>"
        )
        project.repositories.maven { it.url = dir.resolve("repo").toFile().toURI() }
        project.dependencies.add("modImplementation", "com.example:mod:1.0")

        // the consumer contract: lenient + module-component filter —
        // deps without a sources variant and file deps contribute
        // nothing instead of failing or self-attaching
        val resolved = project.configurations
            .getByName("modImplementationSourcesDirect")
            .incoming.artifactView { v ->
                v.lenient(true)
                v.componentFilter {
                    it is org.gradle.api.artifacts.component.ModuleComponentIdentifier
                }
            }.files.map { it.name }
        assertEquals(listOf("mod-1.0-sources.jar"), resolved)
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
    fun `combine propagates auto-discovered resource access docs into child apply spec`() {
        val project = project(listOf("xyz.wagyourtail.unimined.transform"))
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        val resources = dir.resolve("src/main/resources")
        java.nio.file.Files.createDirectories(resources)
        val discovered = resources.resolve("emotes.classtweaker").toFile()
        discovered.writeText(
            "classTweaker\tv1\tnamed\n" +
                    "accessible\tclass\ta/B\n"
        )
        ext.sourceSet("main") {
            it.version.set("1.20.1")
            it.devNamespace.set("named")
        }
        ext.sourceSet("fabric") {
            it.version.set("1.20.1")
            it.devNamespace.set("named")
        }
        ext.sourceSet("fabric").combineWith("main")
        (project as org.gradle.api.internal.project.ProjectInternal).evaluate()

        val parent = ext.sourceSet("main")
        val child = ext.sourceSet("fabric")
        assertTrue(parent.accessWideners.files.contains(discovered),
            "the parent's own post-DSL scan must discover the resource doc")
        assertTrue(child.accessWideners.files.contains(discovered),
            "the combine edge must carry the parent's lazily-discovered doc")

        val remap = project.extensions.getByType(
            xyz.wagyourtail.unimined.remap.RemapExtension::class.java)
        val applySpecs = remap.sourceSet("fabric")
            .contributedSteps(xyz.wagyourtail.unimined.remap.spi.RemapSurface.JAR)
            .filterIsInstance<xyz.wagyourtail.unimined.remapper.access
                    .ApplyClassTweakerStepSpec>()
        val ownSpec = applySpecs.single { spec ->
            spec.classTweakerFiles.from.containsAll(
                listOf(child.accessWideners, child.accessTransformers,
                    child.generatedAccessFiles))
        }
        assertTrue(ownSpec.classTweakerFiles.files.contains(discovered),
            "the child's dev-artifact apply step must see the inherited scan result")
        assertTrue(child.generatedAccessFiles.files.none {
            it.name.startsWith("main-access")
        }, "generated parent docs must not double-propagate as files")
    }


    @Test
    fun `combine chain inherits access dsl recursively after later edge declarations`() {
        val project = project(listOf("xyz.wagyourtail.unimined.transform"))
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        ext.main.version.set("1.20.1")
        ext.main.devNamespace.set("named")
        ext.main.access { it.accessible("a/B") }
        ext.sourceSet("common") {
            it.version.set("1.20.1")
            it.devNamespace.set("named")
        }
        ext.sourceSet("fabric") {
            it.version.set("1.20.1")
            it.devNamespace.set("named")
        }
        // declare the child edge before the intermediate edge — the
        // semantic chain must resolve at post-DSL materialization,
        // not by snapshotting the intermediate env at call time
        ext.sourceSet("fabric").combineWith("common")
        ext.sourceSet("common").combineWith("main")
        (project as org.gradle.api.internal.project.ProjectInternal).evaluate()

        val generated = ext.sourceSet("fabric").generatedAccessFiles.files.single()
        assertTrue(generated.readText().contains("accessible\tclass\ta/B"))
    }


    @Test
    fun `loaderless yarn declarations gain the intermediary hop outside jarmod wiring`() {
        val project = project(listOf("xyz.wagyourtail.unimined.transform"))
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        ext.registry.register(YarnIntermediaryFakeProvider(project))
        val env = ext.main
        env.version.set("1.20.1")
        env.provider.set("fake")
        env.mappings {
            it.yarn(1)
        }
        (project as org.gradle.api.internal.project.ProjectInternal).evaluate()

        val mavenSources = env.mappingsSpec.effectiveSources()
            .filterIsInstance<xyz.wagyourtail.unimined.mapping.MappingsSpecImpl.Source.Maven>()
            .map { it.notation }
        assertTrue(mavenSources.any {
            it.startsWith("net.fabricmc:intermediary:1.20.1")
        }, "yarn needs its upstream intermediary hop: $mavenSources")
        assertTrue(mavenSources.any { ":yarn:" in it })
    }

    @Test
    fun `access discovery skips resource roots under the build dir`() {
        // a build-dir root is a task output — the env's own generated
        // metadata lands there and must not come back as an authored doc
        val project = project(listOf("xyz.wagyourtail.unimined.transform"))
        val doc = "accessWidener\tv1\tnamed\naccessible\tclass\ta/B\n"
        val authored = dir.resolve("src/main/resources/own.accesswidener").toFile()
        val generated = dir.resolve("build/generated/res/gen.accesswidener").toFile()
        for (f in listOf(authored, generated)) {
            f.parentFile.mkdirs()
            f.writeText(doc)
        }
        project.extensions.getByType(org.gradle.api.tasks.SourceSetContainer::class.java)
            .getByName("main").resources.srcDir(generated.parentFile)
        val env = project.extensions.getByType(MinecraftExtension::class.java).main
        env.devNamespace.set("named")
        (project as org.gradle.api.internal.project.ProjectInternal).evaluate()

        val names = env.accessWideners.files.map { it.name }
        assertTrue("own.accesswidener" in names, "authored doc must be discovered: $names")
        assertFalse("gen.accesswidener" in names, "build-dir doc must be skipped: $names")
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
        // canonicalize: on macOS the temp dir is a /var -> /private/var
        // symlink and the two spellings never relativize (upstream #226)
        assertEquals("META-INF/custom_at.cfg",
            gen.canonicalFile.relativeTo(
                dir.resolve("build/unimined/access/main/jar")
                    .toFile().canonicalFile
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
        assertEquals(2, result.declared.size)
        assertTrue(result.declared.any {
            it.fileName.toString().contains("modid.accesswidener") })
        assertTrue(result.declared.any {
            it.fileName.toString().contains("name.txt") })
        // synthesized II doc carries transitive inject-interface rows
        val ii = result.interfaceInjections.single {
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
    fun `dep access scan reads qmj vocabulary`() {
        // quilt dep jar: `access_widener` snake_case doc ref +
        // top-level `quilt_loom.injected_interfaces` (quilt-loom's
        // dep-read contract — NOT inside `custom`)
        val jar = dir.resolve("quilt-dep-1.0.jar")
        java.util.jar.JarOutputStream(
            java.io.FileOutputStream(jar.toFile()),
            java.util.jar.Manifest().apply {
                mainAttributes.putValue("Manifest-Version", "1.0")
            }).use { out ->
            fun put(name: String, content: String) {
                out.putNextEntry(java.util.jar.JarEntry(name))
                out.write(content.toByteArray())
                out.closeEntry()
            }
            put("quilt.mod.json", """{"schema_version":1,
                "quilt_loader":{"id":"qdep","version":"1.0.0"},
                "access_widener":"qdep.aw",
                "quilt_loom":{"injected_interfaces":
                    {"net/mc/A":["com/x/II"]}}}""")
            put("qdep.aw",
                "accessWidener\tv2\tintermediary\n" +
                        "transitive-accessible\tclass\ta/B\n")
        }
        val result = xyz.wagyourtail.unimined.transform.internal
            .DepAccessScan.scan(
                jar, dir.resolve("cache"), "intermediary") {}

        // `access_widener`-referenced doc + synthesized II doc → dep channel
        assertTrue(result.declared.any {
            it.fileName.toString().endsWith("qdep.aw") })
        val ii = result.interfaceInjections.single {
            it.fileName.toString().contains("injected-interfaces") }
        assertTrue(ii.toFile().readText().contains(
            "transitive-inject-interface\tnet/mc/A\tcom/x/II"))
        assertEquals(0, result.ats.size)
    }

    @Test
    fun `dep access scan reads qmj5 vocabulary`() {
        // qmj5 dep jar: JSON5 quilt.mod.json — comments, trailing
        // commas, single quotes, bare keys
        val jar = dir.resolve("quilt5-dep-1.0.jar")
        java.util.jar.JarOutputStream(
            java.io.FileOutputStream(jar.toFile()),
            java.util.jar.Manifest().apply {
                mainAttributes.putValue("Manifest-Version", "1.0")
            }).use { out ->
            fun put(name: String, content: String) {
                out.putNextEntry(java.util.jar.JarEntry(name))
                out.write(content.toByteArray())
                out.closeEntry()
            }
            put("quilt.mod.json", """{
                schema_version: 1,
                quilt_loader: {id: 'q5dep', version: "1.0.0",},
                // widener reference
                access_widener: "q5dep.aw",
                quilt_loom: {
                    injected_interfaces: {"net/mc/B": ["com/x/II"],},
                },
            }""")
            put("q5dep.aw",
                "accessWidener\tv2\tintermediary\n" +
                        "transitive-accessible\tclass\ta/C\n")
        }
        val result = xyz.wagyourtail.unimined.transform.internal
            .DepAccessScan.scan(
                jar, dir.resolve("cache5"), "intermediary") {}

        assertTrue(result.declared.any {
            it.fileName.toString().endsWith("q5dep.aw") })
        val ii = result.interfaceInjections.single {
            it.fileName.toString().contains("injected-interfaces") }
        assertTrue(ii.toFile().readText().contains(
            "transitive-inject-interface\tnet/mc/B\tcom/x/II"))
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



private class YarnIntermediaryFakeArtifacts(private val project: Project) : ProductArtifacts {
    override val artifacts: Map<ArtifactKey, Provider<RegularFile>> = emptyMap()
    override val obfuscatedAuxArtifacts: Provider<List<Provider<RegularFile>>> =
        project.provider { emptyList() }
    override val obfuscated: Provider<Boolean> = project.provider { true }
    override val mappings: Provider<MappingTree> = project.provider { null }
    override val namespaceMode: Provider<NamespaceMode> =
        project.provider { NamespaceMode.REMAPPABLE }
    override val capabilities = project.provider {
        EnvironmentCapabilities(minecraftVersion = "1.20.1")
    }
    override val libraries: Provider<List<Dependency>> = project.provider { emptyList() }
    override val extraDependencies = project.objects.fileCollection()
    override val extraCompileSources = project.objects.fileCollection()
    override val assets: Provider<AssetIndex> = project.provider { null }
    override val launch: LaunchMetadata? = null
}

private class YarnIntermediaryFakeProvider(private val project: Project) : ProductProvider {
    override val name = "fake"
    override fun supports(spec: ProductSpec) = spec.version.version == "1.20.1"
    override fun resolve(spec: ProductSpec, ctx: ProviderContext): ProductArtifacts =
        YarnIntermediaryFakeArtifacts(project)
}
}
