package xyz.wagyourtail.unimined.minecraft

import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import xyz.wagyourtail.unimined.api.spec.Side
import xyz.wagyourtail.unimined.minecraft.fakes.FakeProvider
import xyz.wagyourtail.unimined.minecraft.spi.ArtifactKey
import java.nio.file.Path

/**
 * Loom `splitEnvironmentSourceSets()` parity — the DSL retargets
 * `main` to the COMMON (client∩server intersection) artifact and
 * binds a `client` environment on the full client jar, with `main`'s
 * output reaching the client's classpaths via the implicit-parent
 * combine and client output folding back into the packaged jars.
 */
class SplitEnvironmentSourceSetsTest {

    @TempDir
    lateinit var dir: Path

    private fun project() = ProjectBuilder.builder()
        .withProjectDir(dir.toFile())
        .build()
        .also {
            it.plugins.apply("java")
            it.plugins.apply(GradlePlugin::class.java)
        }

    private fun evaluate(project: org.gradle.api.Project) =
        (project as org.gradle.api.internal.project.ProjectInternal).evaluate()

    @BeforeEach
    fun resetFake() = FakeProvider.reset()

    @Test
    fun `split retargets main to COMMON and binds an aux client env`() {
        val project = project()
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        ext.version.set("1.0")
        ext.splitEnvironmentSourceSets()
        evaluate(project)

        val main = ext.main
        assertEquals(Side.COMMON, main.side.get())
        assertEquals("client", main.splitClientEnvironment.get())

        val client = ext.environments.getByName("client")
        assertEquals(Side.CLIENT, client.side.get())
        assertTrue(client.isAuxEnv.get(),
            "the split client env must be auxiliary — the packaged " +
                    "mod is main's artifact")
        assertFalse(client.defaultRemapJar.get(),
            "a client-only remap jar would duplicate the combined " +
                    "remapJar output")
        assertEquals("main", client.parentEnvId)

        // the client source set exists and bound — its own jar task
        // registered (client-only output for staging/inspection)
        val sourceSets = project.extensions.getByType(
            org.gradle.api.tasks.SourceSetContainer::class.java)
        assertNotNull(sourceSets.findByName("client"))
        assertNotNull(project.tasks.findByName("clientJar"))

        // idempotent — a second call neither re-creates nor re-mutates
        ext.splitEnvironmentSourceSets()
        assertEquals("client", main.splitClientEnvironment.get())
        assertEquals(Side.COMMON, main.side.get())
    }

    @Test
    fun `client classpath sees main output and its declared deps`() {
        val project = project()
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        ext.version.set("1.0")
        ext.splitEnvironmentSourceSets()
        evaluate(project)

        val sourceSets = project.extensions.getByType(
            org.gradle.api.tasks.SourceSetContainer::class.java)
        val main = sourceSets.getByName("main")

        // the implicit-parent combine wires main's output into the
        // client's classpaths — classesDirs on compileOnly (loom:
        // client compileClasspath += main output), the full output on
        // runtimeOnly so main's resources ship
        fun depFiles(confName: String) =
            project.configurations.getByName(confName)
                .allDependencies
                .filterIsInstance<org.gradle.api.artifacts
                        .FileCollectionDependency>()
                .flatMap { it.files.files }
                .toSet()

        assertTrue(
            main.output.classesDirs.files.all { it in depFiles("clientCompileOnly") },
            "client compile classpath should carry main's classesDirs"
        )
        assertTrue(
            main.output.files.all { it in depFiles("clientRuntimeOnly") },
            "client runtime classpath should carry main's full output"
        )
        // DEPENDENCIES section — client's plain buckets extendFrom
        // main's so declared deps reach the client compile classpath
        assertTrue(
            project.configurations.getByName("clientImplementation")
                .extendsFrom.contains(
                    project.configurations.getByName("implementation")),
            "client env should extend main's implementation bucket"
        )
    }

    @Test
    fun `packaged jar folds the client output in`() {
        val project = project()
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        ext.version.set("1.0")
        ext.splitEnvironmentSourceSets()
        evaluate(project)

        // loom `jar.from(clientOnlySourceSet.output)` — prove the fold
        // declaratively: one of the jar spec's source paths resolves
        // to the client source set's output dirs (executing copy()
        // needs task history, and the resolver's source tree drops
        // nonexistent dirs — neither works under ProjectBuilder)
        val sourceSets = project.extensions.getByType(
            org.gradle.api.tasks.SourceSetContainer::class.java)
        val clientOutput = sourceSets.getByName("client").output.files
        val jar = project.tasks.getByName("jar") as org.gradle.jvm.tasks.Jar
        val spec = jar.rootSpec as
                org.gradle.api.internal.file.copy.DefaultCopySpec
        val allPaths = spec.sourcePaths + spec.children.flatMap {
            (it as org.gradle.api.internal.file.copy.DefaultCopySpec)
                .sourcePaths
        }
        assertTrue(clientOutput.isNotEmpty() && allPaths.any { path ->
            project.files(path).files.containsAll(clientOutput)
        },
            "main jar copy spec must carry the client source set's " +
                    "output (loom jar.from(clientOnlySourceSet.output))" +
                    "\nclientOutput=$clientOutput" +
                    "\npaths=${allPaths.map { project.files(it).files }}")
    }

    @Test
    fun `COMMON-keyed artifacts negotiate through the provider ladder`() {
        val project = project()
        val ext = project.extensions.getByType(MinecraftExtension::class.java)
        ext.version.set("1.0")
        ext.splitEnvironmentSourceSets()

        val artifacts = ext.artifacts().get()
        assertNotNull(artifacts.artifacts[ArtifactKey.COMMON],
            "the common env's artifact map must carry a COMMON key")
        assertEquals(Side.COMMON, FakeProvider.sides.last(),
            "the provider contract sees side=COMMON for main")

        // the client env negotiates the full client jar
        val clientArtifacts = ext.artifacts("client").get()
        assertNotNull(clientArtifacts.artifacts[ArtifactKey.CLIENT])
    }

}
