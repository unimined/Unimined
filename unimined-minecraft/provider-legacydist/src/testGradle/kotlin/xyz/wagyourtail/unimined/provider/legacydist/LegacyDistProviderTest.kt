package xyz.wagyourtail.unimined.provider.legacydist

import org.gradle.api.Action
import org.gradle.api.GradleException
import org.gradle.api.artifacts.dsl.DependencyFactory
import org.gradle.api.logging.Logging
import org.gradle.process.ExecOperations
import org.gradle.process.ExecResult
import org.gradle.process.ExecSpec
import org.gradle.process.JavaExecSpec
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import xyz.wagyourtail.unimined.api.spec.DeclaredSource
import xyz.wagyourtail.unimined.api.spec.DeclaredSourceSpec
import xyz.wagyourtail.unimined.api.spec.ProductSpec
import xyz.wagyourtail.unimined.api.spec.ProductVersionSpec
import xyz.wagyourtail.unimined.api.spec.Side
import xyz.wagyourtail.unimined.minecraft.internal.RuntimeVersionSpec
import xyz.wagyourtail.unimined.minecraft.spi.ArtifactKey
import xyz.wagyourtail.unimined.minecraft.spi.NamespaceMode
import xyz.wagyourtail.unimined.minecraft.spi.ProviderContext
import java.io.File

/** `legacydist` provider: declared-source supports/materialization. */
class LegacyDistProviderTest {

    @TempDir
    lateinit var projectDir: File

    private fun ctx(): ProviderContext {
        val project = ProjectBuilder.builder()
            .withProjectDir(projectDir).build()
        return object : ProviderContext {
            override val layout = project.layout
            override val objects = project.objects
            override val dependencyFactory = (project as
                    org.gradle.api.internal.project.ProjectInternal)
                .services.get(DependencyFactory::class.java)
            override val providers = project.providers
            override val exec = (project as
                    org.gradle.api.internal.project.ProjectInternal)
                .services.get(ExecOperations::class.java)
            override val logger =
                Logging.getLogger(LegacyDistProvider::class.java)
            override val javaToolchains = (project as
                    org.gradle.api.internal.project.ProjectInternal)
                .services.get(
                    org.gradle.jvm.toolchain.JavaToolchainService::class.java
                )
            override val useToolchains = project.providers.provider { true }
            override val offline = false
            override val forceReload = false
            override val refreshDependencies = false
            override val refreshDownloads = false
            override val rebuildArtifacts = false
            override val metadataCacheTtl = null
        }
    }

    private fun spec(source: DeclaredSource, format: String? = null) =
        object : ProductSpec {
            override val environmentId = "main"
            override val version: ProductVersionSpec =
                RuntimeVersionSpec("bta-7.3")
            override val side = Side.MERGED
            override val loader = null
            override val declaredSource =
                DeclaredSourceSpec(source, "official", format)
        }

    @Test
    fun `supports only declared-source specs`() {
        val provider = LegacyDistProvider()
        assertTrue(provider.supports(spec(DeclaredSource.LocalFile("x.jar"))))
        assertFalse(provider.supports(object : ProductSpec {
            override val version = RuntimeVersionSpec("1.0")
            override val side = Side.CLIENT
            override val loader = null
        }))
    }

    @Test
    fun `declared source outranks the loader-gated catch-all`() {
        // a `custom { file() }` env without a loader claims both
        // `legacydist` (declaredSource != null, auto-registered from
        // META-INF/services) and a native-shaped `loader == null`
        // provider — at equal priority that was an ambiguity error;
        // the priority bump is the fix. Register a stand-in for
        // `native` (priority 0, loader-gated) since the real provider
        // is not on this module's classpath.
        val registry = xyz.wagyourtail.unimined.minecraft
            .ProductProviderRegistry()
        registry.register(object :
            xyz.wagyourtail.unimined.minecraft.spi.ProductProvider {
            override val name = "native-standin"
            override fun supports(spec: ProductSpec) = spec.loader == null
            override fun resolve(
                spec: ProductSpec, ctx: ProviderContext
            ) = throw UnsupportedOperationException()
        })
        assertTrue(
            registry.probe(spec(DeclaredSource.LocalFile("x.jar")))
                    is LegacyDistProvider
        )
    }

    @Test
    fun `local file source materializes into content cache`() {
        val src = File(projectDir, "src.jar")
            .apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val artifacts = LegacyDistProvider()
            .resolve(spec(DeclaredSource.LocalFile(src.name)), ctx())
        val jar = artifacts.artifacts[ArtifactKey.MERGED]!!.get().asFile
        assertTrue(jar.exists())
        assertEquals(byteArrayOf(1, 2, 3).toList(), jar.readBytes().toList())
        assertEquals(
            NamespaceMode.IDENTITY, artifacts.namespaceMode.get()
        )
    }

    @Test
    fun `obf format reports remappable namespace mode`() {
        File(projectDir, "obf.jar").writeBytes(byteArrayOf(9))
        val artifacts = LegacyDistProvider().resolve(
            spec(DeclaredSource.LocalFile("obf.jar"), format = "obf"), ctx()
        )
        assertEquals(
            NamespaceMode.REMAPPABLE, artifacts.namespaceMode.get()
        )
    }

    @Test
    fun `producer source materializes via the declared command`() {
        // `java -cp <test classpath> WriteFileMain out.jar` — the
        // provider's runProducer seam execs real processes
        val java = File(
            System.getProperty("java.home"), "bin/java"
        ).absolutePath
        val artifacts = LegacyDistProvider().resolve(
            spec(DeclaredSource.Producer(
                executable = java,
                jvmArgs = listOf(
                    "-cp", System.getProperty("java.class.path")
                ),
                args = listOf(WriteFileMain::class.java.name, "out.jar"),
                output = "out.jar"
            )), ctx()
        )
        val jar = artifacts.artifacts[ArtifactKey.MERGED]!!.get().asFile
        assertEquals(byteArrayOf(42).toList(), jar.readBytes().toList())
    }

    @Test
    fun `maven source is rejected with a clear error`() {
        val artifacts = LegacyDistProvider().resolve(
            spec(DeclaredSource.Maven("g:a:1.0")), ctx()
        )
        assertThrows(GradleException::class.java) {
            artifacts.artifacts[ArtifactKey.MERGED]!!.get()
        }
    }
}
