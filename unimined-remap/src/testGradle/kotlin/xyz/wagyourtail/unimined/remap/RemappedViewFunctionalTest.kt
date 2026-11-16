package xyz.wagyourtail.unimined.remap

import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import xyz.wagyourtail.unimined.api.mapping.MappingBundle
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream

/**
 * Phase-0 mechanism validation: a module dependency resolved through a
 * remapped view must come out of the transforms cache with the declared
 * pipeline applied — config-cache clean. Uses the real `tiny` engine:
 * `withPluginClasspath` injects the test runtime classpath, so the engine
 * jar's `META-INF/services` registration is visible to the transform.
 */
class RemappedViewFunctionalTest {

    @TempDir
    lateinit var dir: Path

    private fun writeRepo(): Path {
        val libDir = dir.resolve("repo/com/example/lib/1.0")
        Files.createDirectories(libDir)
        JarOutputStream(Files.newOutputStream(libDir.resolve("lib-1.0.jar"))).use { jar ->
            jar.putNextEntry(JarEntry("original.txt"))
            jar.write("data".toByteArray())
            jar.closeEntry()
        }
        Files.writeString(
            libDir.resolve("lib-1.0.pom"),
            """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
                <modelVersion>4.0.0</modelVersion>
                <groupId>com.example</groupId>
                <artifactId>lib</artifactId>
                <version>1.0</version>
            </project>
            """.trimIndent()
        )
        return dir.resolve("repo")
    }

    private fun writeMultiNamespaceRepo(): Path {
        for ((artifact, marker) in mapOf(
            "official-mod" to "official.txt",
            "intermediary-mod" to "intermediary.txt"
        )) {
            val libDir = dir.resolve("repo/com/example/$artifact/1.0")
            Files.createDirectories(libDir)
            JarOutputStream(Files.newOutputStream(libDir.resolve("$artifact-1.0.jar"))).use { jar ->
                jar.putNextEntry(JarEntry(marker))
                jar.write("data".toByteArray())
                jar.closeEntry()
            }
            Files.writeString(
                libDir.resolve("$artifact-1.0.pom"),
                """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>$artifact</artifactId>
                    <version>1.0</version>
                </project>
                """.trimIndent()
            )
        }
        return dir.resolve("repo")
    }

    private fun writeMultiNamespaceProject(repo: Path) {
        Files.writeString(dir.resolve("settings.gradle"), "rootProject.name = 't'\n")
        Files.writeString(
            dir.resolve("build.gradle"),
            """
            plugins {
                id 'java'
                id 'xyz.wagyourtail.unimined.remap'
            }

            repositories {
                maven { url = uri('${repo.toUri()}') }
            }

            def remapped = uniminedRemap.remappedViews("mods") {
                targetNamespace.set("named")
                namespace("official", "officialMods")
                namespace("intermediary", "intermediaryMods")
            }

            dependencies {
                officialMods "com.example:official-mod:1.0"
                intermediaryMods "com.example:intermediary-mod:1.0"
            }

            tasks.register("copyRemapped", Copy) {
                from(remapped)
                into layout.buildDirectory.dir("remapped")
            }
            """.trimIndent()
        )
    }

    private fun writeProject(repo: Path) {
        // an empty row set: tiny still runs the pipeline, the jar passes
        // through unrenamed — the transform firing is observable via the
        // artifact's per-namespace output name
        MappingBundle(
            namespaces = listOf("official", "named"),
            source = "official",
            target = "named",
            rows = emptyList()
        ).write(dir.resolve("mappings.json"))

        Files.writeString(dir.resolve("settings.gradle"), "rootProject.name = 't'\n")
        Files.writeString(
            dir.resolve("build.gradle"),
            """
            plugins { id 'java' }
            // legacy apply: resolves via the buildscript classpath's
            // META-INF/gradle-plugins marker — plugins{}-DSL resolution
            // needs a plugin repository the TestKit project lacks
            apply plugin: 'xyz.wagyourtail.unimined.remap'

            repositories {
                maven { url = uri('${repo.toUri()}') }
            }

            uniminedRemap {
                jar { remap("tiny") }
            }

            configurations.create("deps")
            dependencies { deps "com.example:lib:1.0" }

            def remapped = uniminedRemap.remappedView("deps", "depsRemapped") {
                it.sourceNamespace.set("official")
                it.targetNamespace.set("named")
                it.mappingFile.fileValue(file('mappings.json'))
            }

            tasks.register("copyRemapped", Copy) {
                from(remapped)
                into layout.buildDirectory.dir("remapped")
            }
            """.trimIndent()
        )
    }

    @Test
    fun `multiple source namespaces resolve to one target view set`() {
        val repo = writeMultiNamespaceRepo()
        writeMultiNamespaceProject(repo)

        GradleRunner.create()
            .withProjectDir(dir.toFile())
            .withPluginClasspath()
            .withArguments("copyRemapped", "--configuration-cache", "-s")
            .build()

        val jars = Files.list(dir.resolve("build/remapped"))
            .map { it.fileName.toString() }
            .sorted()
            .toList()
        assertEquals(
            listOf("intermediary-mod-1.0-named.jar", "official-mod-1.0-named.jar"),
            jars
        )
        JarFile(dir.resolve("build/remapped/intermediary-mod-1.0-named.jar").toFile()).use {
            assertNotNull(it.getEntry("intermediary.txt"))
        }
        JarFile(dir.resolve("build/remapped/official-mod-1.0-named.jar").toFile()).use {
            assertNotNull(it.getEntry("official.txt"))
        }
    }

    /** `withPluginClasspath` injects only this module's *main* runtime
     *  classpath — the `remapper-tiny` engine (a testGradle dep) is
     *  missing, so the transform's classloader ServiceLoader can't see
     *  it. An init script puts the whole test classpath on the
     *  buildscript classpath instead; the plugin + engine then load
     *  from the same classloader graph. */
    private fun writeInitScript(): Path {
        val entries = System.getProperty("java.class.path")
            .split(java.io.File.pathSeparator)
            .joinToString(", ") { "'${it.replace("'", "\\'")}'" }
        val init = dir.resolve("init-classpath.gradle")
        Files.writeString(
            init,
            """
            allprojects { p ->
                p.buildscript {
                    dependencies {
                        classpath p.files($entries)
                    }
                }
            }
            """.trimIndent()
        )
        return init
    }

    @Test
    fun `module dependency resolves through the remap transform`() {
        val repo = writeRepo()
        writeProject(repo)

        GradleRunner.create()
            .withProjectDir(dir.toFile())
            .withArguments("copyRemapped", "--configuration-cache", "-s",
                "-I", writeInitScript().toString())
            .build()

        val outDir = dir.resolve("build/remapped")
        val jars = Files.list(outDir).filter { it.fileName.toString().endsWith(".jar") }.toList()
        assertEquals(listOf("lib-1.0-named.jar"), jars.map { it.fileName.toString() })
        JarFile(jars.single().toFile()).use { jar ->
            assertNotNull(jar.getEntry("original.txt"))
        }
    }

    /**
     * Marker-gated remap decisions: a marked jar honoring
     * `Fabric-Loom-Remap:false` and a plain jar pass through; a plain
     * jar covered by `forceRemap` and an unmarked `…:true` jar remap.
     */
    @Test
    fun `loom remap attribute and forceRemap steer the marker gate`() {
        val cls = "xyz/wagyourtail/unimined/remap/fakes/FakeStep"
        fun writeJar(dir: Path, name: String, fmj: Boolean,
                     loomRemap: Boolean?, realClass: Boolean) {
            Files.createDirectories(dir)
            val manifest = java.util.jar.Manifest().apply {
                mainAttributes.putValue("Manifest-Version", "1.0")
                if (loomRemap != null) {
                    mainAttributes.putValue("Fabric-Loom-Remap", loomRemap.toString())
                }
            }
            JarOutputStream(Files.newOutputStream(dir.resolve("$name-1.0.jar")), manifest).use { jar ->
                jar.putNextEntry(JarEntry("data.txt"))
                jar.write("x".toByteArray())
                jar.closeEntry()
                if (fmj) {
                    jar.putNextEntry(JarEntry("fabric.mod.json"))
                    jar.write("""{"schemaVersion":1,"id":"m","version":"1"}""".toByteArray())
                    jar.closeEntry()
                }
                if (realClass) {
                    // a real class file — UML's mod-artifacts pass
                    // validates the official→named layer renames >0
                    jar.putNextEntry(JarEntry("$cls.class"))
                    jar.write(javaClass.getResourceAsStream("/$cls.class")!!.readBytes())
                    jar.closeEntry()
                }
            }
            Files.writeString(dir.resolve("$name-1.0.pom"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>$name</artifactId>
                    <version>1.0</version>
                </project>
            """.trimIndent())
        }

        val repo = dir.resolve("repo")
        writeJar(repo.resolve("com/example/optout/1.0"), "optout", fmj = true, loomRemap = false, realClass = true)
        writeJar(repo.resolve("com/example/optin/1.0"), "optin", fmj = false, loomRemap = true, realClass = true)
        writeJar(repo.resolve("com/example/forced/1.0"), "forced", fmj = false, loomRemap = null, realClass = true)
        writeJar(repo.resolve("com/example/plain/1.0"), "plain", fmj = false, loomRemap = null, realClass = true)

        MappingBundle(
            namespaces = listOf("official", "named"),
            source = "official",
            target = "named",
            rows = listOf(
                MappingBundle.Row(
                    MappingBundle.Kind.CLASS,
                    srcName = cls, dstName = "moved/FakeStep"
                )
            )
        ).write(dir.resolve("mappings.json"))
        Files.writeString(dir.resolve("settings.gradle"), "rootProject.name = 't'\n")
        Files.writeString(dir.resolve("build.gradle"), """
            plugins { id 'java' }
            apply plugin: 'xyz.wagyourtail.unimined.remap'

            repositories { maven { url = uri('${repo.toUri()}') } }

            uniminedRemap { jar { remap("tiny") } }

            configurations.create("deps")
            dependencies {
                deps "com.example:optout:1.0"
                deps "com.example:optin:1.0"
                deps "com.example:forced:1.0"
                deps "com.example:plain:1.0"
            }

            def remapped = uniminedRemap.remappedView("deps", "depsRemapped") {
                it.sourceNamespace.set("official")
                it.targetNamespace.set("named")
                it.mappingFile.fileValue(file('mappings.json'))
                it.onlyRemapMarkedJars.set(true)
                it.modMarkers.set(["file:fabric.mod.json"])
                it.discriminator.set("modgate")
                it.forceRemap("com.example:forced:1.0")
            }

            tasks.register("copyRemapped", Copy) {
                from(remapped)
                into layout.buildDirectory.dir("remapped")
            }
        """.trimIndent())

        GradleRunner.create()
            .withProjectDir(dir.toFile())
            .withArguments("copyRemapped", "--configuration-cache", "-s",
                "-I", writeInitScript().toString())
            .build()

        // transform output always carries the -<ns> suffix — the gate
        // verdict shows in CONTENT: remapped jars have FakeStep under
        // `moved/`, pass-through jars keep it at its original name
        val remappedOut = Files.list(dir.resolve("build/remapped"))
            .map { it.fileName.toString() }.sorted().toList()
        assertEquals(
            listOf("forced-1.0-named.jar", "optin-1.0-named.jar",
                "optout-1.0-named.jar", "plain-1.0-named.jar"),
            remappedOut
        )
        fun classEntry(name: String): String? =
            JarFile(dir.resolve("build/remapped/$name").toFile()).use { jar ->
                jar.entries().toList().firstOrNull { it.name.endsWith(".class") }?.name
            }
        assertEquals("moved/FakeStep.class", classEntry("forced-1.0-named.jar"))
        assertEquals("moved/FakeStep.class", classEntry("optin-1.0-named.jar"))
        assertEquals("$cls.class", classEntry("optout-1.0-named.jar"))
        assertEquals("$cls.class", classEntry("plain-1.0-named.jar"))
    }

}
