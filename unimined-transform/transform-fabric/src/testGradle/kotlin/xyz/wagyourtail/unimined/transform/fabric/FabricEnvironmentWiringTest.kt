package xyz.wagyourtail.unimined.transform.fabric

import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import xyz.wagyourtail.unimined.minecraft.MinecraftExtension
import xyz.wagyourtail.unimined.remap.AccessRemapStepSpec
import xyz.wagyourtail.unimined.remap.RemapExtension
import xyz.wagyourtail.unimined.remap.RemapStepSpec
import xyz.wagyourtail.unimined.remap.spi.RemapSurface
import xyz.wagyourtail.unimined.remapper.access.ApplyClassTweakerStepSpec
import xyz.wagyourtail.unimined.transform.TransformExtension
import java.nio.file.Path

/**
 * Verifies the fabric transform's per-environment contributions: loader
 * dependency injection, mod-source namespace default, contributed JAR
 * pipeline steps, and the run mutator — all gated on `loader("fabric")`.
 */
class FabricEnvironmentWiringTest {

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

    private fun fabricEnv(
        project: org.gradle.api.Project,
        ext: MinecraftExtension
    ) = org.gradle.api.tasks.SourceSetContainer::class.java.let { type ->
        project.extensions.getByType(type).create("fabric")
        ext.sourceSet("fabric") { env ->
            env.version.set("1.20.1")
            env.loader("fabric", "0.16.0")
        }
    }

    @Test
    fun `apply contributes fabric repo and intermediary mod-source default`() {
        val project = project()
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        assertEquals(listOf("intermediary"), ext.loaderModSourceNamespaces["fabric"])
        assertTrue(
            project.repositories.any { (it as? org.gradle.api.artifacts.repositories.MavenArtifactRepository)?.url.toString() == "https://maven.fabricmc.net/" }
        )
    }

    @Test
    fun `fabric env gets loader dep, contributed steps, run mutator`() {
        val project = project()
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        val env = fabricEnv(project, ext)

        val remap = project.extensions.getByType(RemapExtension::class.java)
        val steps = remap.pipelineSteps("fabric", RemapSurface.JAR).get()
        assertEquals(3, steps.size)
        assertTrue(steps[0] is RemapStepSpec)
        assertTrue(steps[1] is ApplyClassTweakerStepSpec)
        assertTrue(steps[2] is AccessRemapStepSpec)

        // own AW/CTs and dependency-extracted files feed separate
        // collections — dependencies apply through the transitive-only
        // filter
        val awSpec = steps[1] as ApplyClassTweakerStepSpec
        assertTrue(awSpec.classTweakerFiles !== awSpec.dependencyClassTweakerFiles)
        assertEquals(true, env.transitiveAccessWideners.get())

        val transform = project.extensions.getByType(TransformExtension::class.java)
        assertEquals(1, transform.sourceSet("fabric").runMutators.size)

        assertEquals("fabric", env.loaderSpec.orNull?.name)
        assertEquals(
            listOf("intermediary"),
            ext.modSourceNamespaces("fabric").get()
        )
        assertTrue(env.mappingsSpec.defaultSources.isNotEmpty())
        assertNotNull(project.configurations.findByName("fabricModAnalysisFabric"))

        // resolving the analysis view runs the loader-dep hook end-to-end
        val repo = dir.resolve("repo").toFile()
        val jarDir = repo.resolve("net/fabricmc/fabric-loader/0.16.0")
        jarDir.mkdirs()
        jarDir.resolve("fabric-loader-0.16.0.jar").writeBytes(
            byteArrayOf(0x50, 0x4B, 0x05, 0x06, *ByteArray(18))
        )
        project.repositories.mavenCentral()
        project.repositories.maven {
            it.url = repo.toURI()
            it.metadataSources { sources -> sources.artifact() }
        }
        val resolved = project.configurations.getByName("fabricModAnalysisFabric")
            .resolve().map { it.name }
        // the fabric repo is declared first and serves the real loader
        // jar; its embedded fabric-installer.json adds asm + sponge-mixin
        // which ride the same view onto the mod classpath
        assertTrue("fabric-loader-0.16.0.jar" in resolved)
        assertTrue(resolved.any { it.startsWith("sponge-mixin-") })
        assertTrue(resolved.any { it.startsWith("asm-") })
    }

    @Test
    fun `family members contribute their own loader and intermediary deps`() {
        val project = project()
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        val sourceSets = project.extensions.getByType(
            org.gradle.api.tasks.SourceSetContainer::class.java
        )
        // loader -> (loaderVer, loader GAV, intermediary GAV incl.
        // :v2 classifier) — coords from 1.x MappingsProvider
        val repo = dir.resolve("repo").toFile()
        fun serve(gav: String) {
            // gav = g:a:v or g:a:v:classifier
            val p = gav.split(":")
            val jarDir = repo.resolve("${p[0].replace('.', '/')}/${p[1]}/${p[2]}")
            jarDir.mkdirs()
            val classifier = p.getOrNull(3)?.let { "-$it" } ?: ""
            jarDir.resolve("${p[1]}-${p[2]}$classifier.jar").writeBytes(
                byteArrayOf(0x50, 0x4B, 0x05, 0x06, *ByteArray(18))
            )
        }
        project.repositories.maven {
            it.url = repo.toURI()
            it.metadataSources { s -> s.artifact() }
        }
        val cases = listOf(
            FamilyCase("quilt", "0.25.0", "org.quiltmc:quilt-loader:0.25.0", "net.fabricmc:intermediary:1.20.1:v2"),
            FamilyCase("legacyfabric", "0.16.0", "net.fabricmc:fabric-loader:0.16.0", "net.legacyfabric.v1:intermediary:1.20.1:v2"),
            FamilyCase("babric", "0.16.0", "babric:fabric-loader:0.16.0", "babric:intermediary-upstream:1.20.1:v2"),
            FamilyCase("ornithe", "0.16.0", "net.fabricmc:fabric-loader:0.16.0", "net.ornithemc:calamus-intermediary-gen2:1.20.1:v2"),
            FamilyCase("flint", "1.0.0", "net.flintloader:punch:1.0.0", "net.fabricmc:intermediary:1.20.1:v2"),
        )
        for (c in cases) {
            serve(c.loaderGav)
            serve(c.interGav)
            sourceSets.create(c.loader)
            ext.sourceSet(c.loader) { env ->
                env.version.set("1.20.1")
                env.loader(c.loader, c.loaderVer)
            }
            val capitalized = c.loader.replaceFirstChar { it.uppercase() }
            val loaderJar = c.loaderGav.split(":")[1] +
                "-${c.loaderGav.split(":")[2]}.jar"
            val resolved = project.configurations
                .getByName("fabricLoader$capitalized").resolve()
                .map { it.name }
            assertTrue(loaderJar in resolved, "${c.loader} loader: $resolved")
            val interGav = c.interGav.split(":")
            val interJar = "${interGav[1]}-${interGav[2]}-v2.jar"
            val resolvedInter = project.configurations
                .getByName("fabricIntermediary$capitalized").resolve()
                .map { it.name }
            assertTrue(
                interJar in resolvedInter,
                "${c.loader} intermediary: $resolvedInter"
            )
        }
    }

    private data class FamilyCase(
        val loader: String,
        val loaderVer: String,
        val loaderGav: String,
        val interGav: String
    )

    @Test
    fun `contributes the access model with own aw sections`() {
        val project = project()
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        val env = fabricEnv(project, ext)

        val remap = project.extensions.getByType(RemapExtension::class.java)
        val envSpec = remap.sourceSet("fabric")
        assertEquals(1, envSpec.accessModelProviders.size)

        // a declared own AW lands as a model section in its own
        // namespace, tagged with the declaring file; dep extraction
        // yields nothing until the analysis view resolves
        val aw = dir.resolve("test.accesswidener").toFile()
        aw.writeText(
            "accessWidener\tv2\tintermediary\n" +
                    "accessible\tclass\ta/b/C"
        )
        env.accessWideners.from(aw)
        env.accessWideners.files.forEach { assertTrue(it.isFile) }

        // resolving the model evaluates the dep-AW provider -> resolves
        // the analysis view; serve the loader jar from a local repo
        val repo = dir.resolve("repo").toFile()
        val jarDir = repo.resolve("net/fabricmc/fabric-loader/0.16.0")
        jarDir.mkdirs()
        jarDir.resolve("fabric-loader-0.16.0.jar").writeBytes(
            byteArrayOf(0x50, 0x4B, 0x05, 0x06, *ByteArray(18))
        )
        project.repositories.mavenCentral()
        project.repositories.maven {
            it.url = repo.toURI()
            it.metadataSources { sources -> sources.artifact() }
        }

        val model = envSpec.accessModelProviders.single().get()
        val section = model.sections.single()
        assertEquals("intermediary", section.namespace)
        val entry = section.entries.single()
        assertEquals("a/b/C", entry.owner)
        assertEquals("test.accesswidener", entry.source)
    }

    @Test
    fun `fabric env registers jar processors and fmj spec context`() {
        val project = project()
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        val env = fabricEnv(project, ext)

        val transform = project.extensions.getByType(TransformExtension::class.java)
        val envSpec = transform.sourceSet("fabric")
        assertTrue(envSpec.jarProcessors.any { it.name == "mod-provided-javadoc" })
        assertNotNull(envSpec.jarProcessorSpecContext)

        // the environment's mapping spec gains the jar-processors
        // mapping half — mod-provided javadoc enrichment rides it
        assertTrue(
            env.mappingsSpec.postProcessors.any { it.name == "jar-processor-mappings" }
        )
    }

    @Test
    fun `legacy fabric-family loaders get the LWJGL2 substitution`() {
        val project = project()
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        for ((loader, rule) in mapOf(
            "legacyfabric" to ("org.lwjgl" to "2.9.4+legacyfabric.8"),
            "babric" to ("org.lwjgl" to "2.9.4+legacyfabric.8"),
            "ornithe" to ("org.lwjgl" to "2.9.4+legacyfabric.8"),
            "flint" to ("org.lwjgl.lwjgl" to "2.9.4+legacyfabric.10"),
        )) {
            // loader() before the matching sourceSet: env wiring reads
            // loaderSpec eagerly when the env binds (production order is
            // guaranteed by lazy transform-plugin apply)
            val env = ext.sourceSet(loader) { e ->
                e.version.set("1.20.1")
                e.loader(loader, "1.0")
            }
            project.extensions.getByType(
                org.gradle.api.tasks.SourceSetContainer::class.java
            ).create(loader)
            assertEquals(listOf(rule), env.librarySubstitutions,
                "$loader should carry its 1.x LWJGL2 substitution")
            // the rule reaches the provider-facing spec
            assertEquals(listOf(rule), env.spec().librarySubstitutions)
        }
    }

    @Test
    fun `modern fabric and quilt get no LWJGL swap`() {
        val project = project()
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        val env = ext.sourceSet("quilt") { e ->
            e.version.set("1.20.1")
            e.loader("quilt", "1.0")
        }
        project.extensions.getByType(
            org.gradle.api.tasks.SourceSetContainer::class.java
        ).create("quilt")
        assertEquals(emptyList<Pair<String, String>>(), env.librarySubstitutions)
        assertEquals(
            emptyList<Pair<String, String>>(),
            env.spec().librarySubstitutions
        )
    }

    @Test
    fun `non-fabric environments get no loader dep`() {
        val project = project()
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        ext.version.set("1.20.1")

        val modImpl = project.configurations.getByName("modImplementation")
        assertTrue(modImpl.dependencies.isEmpty())
    }

}
