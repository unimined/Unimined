package xyz.wagyourtail.unimined

import org.gradle.api.Project
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import xyz.wagyourtail.unimined.api.environment.EnvironmentSection
import xyz.wagyourtail.unimined.minecraft.MinecraftEnvironment
import xyz.wagyourtail.unimined.minecraft.MinecraftExtension
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.io.path.outputStream

/**
 * Merged multi-loader environments — `loader("a"); loader("b")` on ONE
 * env (the 1.x `merged { fabric; forge }` analog): the first loader is
 * primary (prod/remap/metadata/run semantics), every additional loader
 * merges its product onto the env's compile classpath. Verified through
 * the real env-binding path: `loaderSpecs` retention, lazy per-loader
 * transform application, the `mergedLoader`/`forgeMergedLoader` buckets,
 * classpath extendsFrom edges, and `combineWith` membership.
 */
class MergedEnvironmentTest {

    @TempDir
    lateinit var dir: Path

    /** `exclusiveContent` rewrites every other repo's content
     *  descriptor — only legal before the first resolution; a repeat
     *  declaration for the same group after resolution throws. */
    private val exclusiveGroups = mutableSetOf<String>()

    private fun pinLocalRepo(group: String) {
        if (!exclusiveGroups.add(group)) return
        project.repositories.exclusiveContent { exclusive ->
            exclusive.forRepository {
                project.repositories.maven {
                    it.url = dir.resolve("repo").toFile().toURI()
                    it.metadataSources { s -> s.mavenPom(); s.artifact() }
                }
            }
            exclusive.filter { it.includeGroup(group) }
        }
    }

    private fun project() = ProjectBuilder.builder()
        .withProjectDir(dir.toFile())
        .build()
        .also { project ->
            project.plugins.apply("java")
            project.plugins.apply("xyz.wagyourtail.unimined.remap")
            project.plugins.apply("xyz.wagyourtail.unimined.minecraft")
            project.plugins.apply("xyz.wagyourtail.unimined.transform")
            // fabric/forge plugins are *not* applied — the transform
            // module's lazy per-loader apply covers them post-eval
            // main's spec must resolve — loader plugins' post-DSL
            // hooks read `environment.obfuscated` on every env
            val ext = project.extensions
                .getByType(MinecraftExtension::class.java)
            ext.main.version.set("1.20.1")
            ext.main.provider.set("fake")
        }

    private fun serve(coords: String, jar: (JarOutputStream.() -> Unit)? = null) {
        val repo = dir.resolve("repo").toFile()
        val (g, a, v) = coords.split(":")
        val jarDir = repo.resolve("${g.replace('.', '/')}/$a/$v")
        jarDir.mkdirs()
        jarDir.resolve("$a-$v.pom").writeText(
            """<project xmlns="http://maven.apache.org/POM/4.0.0">""" +
                    "<modelVersion>4.0.0</modelVersion>" +
                    "<groupId>$g</groupId><artifactId>$a</artifactId>" +
                    "<version>$v</version></project>")
        JarOutputStream(jarDir.resolve("$a-$v.jar").outputStream()).use { out ->
            jar?.invoke(out)
        }
        pinLocalRepo(g)
    }

    /** Serve a probe-valid userdev jar (`config.json` + binpatches —
     *  the minimum `ForgeInstallerProbe` needs for `Userdev`) under the
     *  `:userdev` classifier of [coords] (`g:a:v`). */
    private fun serveUserdev(coords: String) {
        val repo = dir.resolve("repo").toFile()
        val (g, a, v) = coords.split(":")
        val jarDir = repo.resolve("${g.replace('.', '/')}/$a/$v")
        jarDir.mkdirs()
        jarDir.resolve("$a-$v.pom").writeText(
            """<project xmlns="http://maven.apache.org/POM/4.0.0">""" +
                    "<modelVersion>4.0.0</modelVersion>" +
                    "<groupId>$g</groupId><artifactId>$a</artifactId>" +
                    "<version>$v</version></project>")
        JarOutputStream(
            jarDir.resolve("$a-$v-userdev.jar").outputStream()
        ).use { out ->
            out.putNextEntry(JarEntry("config.json"))
            out.write(
                """{"mcp": "de.oceanlabs.mcp:mcp_config:1.20.1@zip"}"""
                    .toByteArray())
            out.closeEntry()
            out.putNextEntry(JarEntry("binpatches.pack.lzma"))
            out.write("x".toByteArray())
            out.closeEntry()
        }
        pinLocalRepo(g)
    }

    private lateinit var project: Project

    private fun env(
        ext: MinecraftExtension,
        id: String,
        action: (MinecraftEnvironment) -> Unit = {}
    ) = ext.sourceSet(id) { e ->
        e.version.set("1.20.1")
        action(e)
    }.also {
        project.extensions.getByType(
            org.gradle.api.tasks.SourceSetContainer::class.java
        ).maybeCreate(id)
    }

    private fun evaluate() =
        (project as org.gradle.api.internal.project.ProjectInternal).evaluate()

    @Test
    fun `repeated loader declarations merge on one environment`() {
        project = project()
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        val common = env(ext, "common") { e ->
            e.loader("fabric", "0.16.0")
            e.loader("forge", "47.3.0")
        }
        // the first declaration is the primary — later ones are
        // compile-classpath secondaries, not a replacement
        assertEquals("fabric", common.loaderSpec.orNull?.name)
        assertEquals(
            listOf("fabric" to "0.16.0", "forge" to "47.3.0"),
            common.loaderSpecs.map { it.name to it.version }
        )
        evaluate()
        // every declared loader's family transform applies, not just
        // the primary's
        assertTrue(project.plugins.hasPlugin(
            "xyz.wagyourtail.unimined.transform.fabric"))
        assertTrue(project.plugins.hasPlugin(
            "xyz.wagyourtail.unimined.transform.forge"))
    }

    @Test
    fun `secondary fabric loader rides the generic merged bucket compile-only`() {
        project = project()
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        env(ext, "common") { e ->
            e.loader("forge", "47.3.0")
            e.loader("fabric", "0.16.0")
        }
        serve("net.fabricmc:fabric-loader:0.16.0") {
            // the loader's only dep declaration — its embedded
            // installer json (the published pom is intentionally empty)
            putNextEntry(JarEntry("fabric-installer.json"))
            write(("""{"libraries":{"common":[""" +
                    """{"name":"org.spongepowered:mixin:0.15.2"}]}}""")
                .toByteArray())
            closeEntry()
        }
        serve("org.spongepowered:mixin:0.15.2")
        serveUserdev("net.minecraftforge:forge:1.20.1-47.3.0")
        evaluate()

        // the secondary's loader jar resolves from the generic bucket —
        // the forge secondary probe is *not* needed here since forge is
        // primary
        val merged = project.configurations
            .getByName("mergedLoaderCommon").resolve().map { it.name }
        assertEquals(listOf("fabric-loader-0.16.0.jar"), merged)

        // its embedded installer libraries ride their own bucket —
        // @Mixin & loader-API deps a common source set needs to compile
        val mergedLibs = project.configurations
            .getByName("fabricMergedLoaderLibrariesCommon")
            .resolve().map { it.name }
        assertEquals(listOf("mixin-0.15.2.jar"), mergedLibs)

        // the primary forge loader resolves its era artifact on its
        // own bucket, as before
        val forgeLoader = project.configurations
            .getByName("forgeLoaderCommon").resolve().map { it.name }
        assertTrue("forge-1.20.1-47.3.0-userdev.jar" in forgeLoader,
            "primary forgeLoader: $forgeLoader")

        // compile classpath unions every loader bucket; runtime keeps
        // only the primary's — merged loaders are compile-only
        // compile classpath unions every loader bucket; runtime keeps
        // only the primary's — merged loaders are compile-only
        fun parents(conf: String) = project.configurations
            .getByName(conf).extendsFrom.map { it.name }.toSet()
        assertTrue("mergedLoaderCommon" in parents("commonCompileClasspath"))
        assertTrue("forgeMergedLoaderCommon" in parents("commonCompileClasspath"))
        assertTrue("forgeLoaderCommon" in parents("commonCompileClasspath"))
        assertTrue("fabricLoaderCommon" in parents("commonCompileClasspath"))
        assertTrue("fabricMergedLoaderLibrariesCommon" in
            parents("commonCompileClasspath"))
        assertFalse("mergedLoaderCommon" in parents("commonRuntimeClasspath"))
        assertFalse("forgeMergedLoaderCommon" in parents("commonRuntimeClasspath"))
        assertFalse("fabricMergedLoaderLibrariesCommon" in
            parents("commonRuntimeClasspath"))
        assertTrue("forgeLoaderCommon" in parents("commonRuntimeClasspath"))
        // fabric is secondary — the primary-scoped `fabricLoader`
        // bucket stays empty; its jar only arrives via mergedLoader
        assertTrue(
            project.configurations.getByName("fabricLoaderCommon")
                .resolve().isEmpty(),
            "fabricLoader conf must be empty when fabric is secondary"
        )
    }

    @Test
    fun `secondary forge loader probes its era artifact onto the compile classpath`() {
        project = project()
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        env(ext, "common") { e ->
            e.loader("fabric", "0.16.0")
            e.loader("forge", "47.3.0")
        }
        serve("net.fabricmc:fabric-loader:0.16.0")
        serveUserdev("net.minecraftforge:forge:1.20.1-47.3.0")
        evaluate()

        // forge self-manages its merged contribution — the generic
        // bucket stays empty for a forge secondary (bare
        // `net.minecraftforge:forge` jars don't exist for FG2-era)
        val merged = project.configurations
            .getByName("mergedLoaderCommon").resolve()
        assertTrue(merged.isEmpty(), "mergedLoader: $merged")

        // the probed `:userdev` artifact lands on the dedicated bucket
        val forgeMerged = project.configurations
            .getByName("forgeMergedLoaderCommon").resolve().map { it.name }
        assertTrue("forge-1.20.1-47.3.0-userdev.jar" in forgeMerged,
            "forgeMergedLoader: $forgeMerged")

        // the primary fabric loader still resolves independently
        val fabricLoader = project.configurations
            .getByName("fabricLoaderCommon").resolve().map { it.name }
        assertEquals(listOf("fabric-loader-0.16.0.jar"), fabricLoader)

        fun parents(conf: String) = project.configurations
            .getByName(conf).extendsFrom.map { it.name }.toSet()
        assertTrue("forgeMergedLoaderCommon" in parents("commonCompileClasspath"))
        assertTrue("fabricLoaderCommon" in parents("commonCompileClasspath"))
        assertFalse("forgeMergedLoaderCommon" in parents("commonRuntimeClasspath"))
        // forge is secondary — its env-semantic contributions stay
        // inert: no forge loader-artifact naming on the dev jar
        assertEquals("minecraft-common-1.20.1",
            env(ext, "common").artifactBaseName.orNull)
    }

    /** Per-loader envs combine from a merged env when their loader is
     *  among its declarations — the shared-source-set shape the merged
     *  env exists for. */
    @Test
    fun `combineWith accepts a declared loader member of the merged source`() {
        project = project()
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        // explicit dev namespaces — the combination check's namespace
        // arm resolves mappings when unset, which needs a product
        // provider the test doesn't host
        ext.main.devNamespace.set("named")
        env(ext, "common") { e ->
            e.devNamespace.set("named")
            e.loader("fabric", "0.16.0")
            e.loader("forge", "47.3.0")
        }
        val forge = env(ext, "forge") { e ->
            e.devNamespace.set("named")
            e.loader("forge", "47.3.0")
        }
        val quilt = env(ext, "quilt") { e ->
            e.devNamespace.set("named")
            e.loader("quilt", "0.25.0")
        }
        forge.combineWith("common")
        quilt.combineWith("common")

        fun validate(e: MinecraftEnvironment) =
            e.javaClass.methods.first {
                it.name.startsWith("validateCombinations")
            }.invoke(e)

        // forge is among common's loaders — combine validates
        validate(forge)
        // quilt is not — rejected (reflection wraps the GradleException)
        val ex = assertThrows(
            java.lang.reflect.InvocationTargetException::class.java) {
            validate(quilt)
        }
        val cause = ex.cause
        assertTrue(cause is org.gradle.api.GradleException &&
                cause.message?.contains("loaders differ") == true,
            "expected loader-membership rejection, got: $cause")
    }

    /** LOADER-section copies propagate merged secondaries — an aux env
     *  (datagen-style) of a merged env sees the full loader set. */
    @Test
    fun `copyFrom LOADER propagates merged secondary loaders`() {
        project = project()
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        env(ext, "common") { e ->
            e.loader("fabric", "0.16.0")
            e.loader("forge", "47.3.0")
        }
        val aux = env(ext, "aux") { }
        aux.copyFrom("common", EnvironmentSection.LOADER)
        assertEquals(
            listOf("fabric" to "0.16.0", "forge" to "47.3.0"),
            aux.loaderSpecs.map { it.name to it.version },
            "aux env must see the source's primary + secondaries"
        )
    }

}
