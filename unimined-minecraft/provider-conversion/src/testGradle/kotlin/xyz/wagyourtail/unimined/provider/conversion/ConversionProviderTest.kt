package xyz.wagyourtail.unimined.provider.conversion

import org.gradle.api.GradleException
import org.gradle.api.artifacts.dsl.DependencyFactory
import org.gradle.api.logging.Logging
import org.gradle.process.ExecOperations
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import xyz.wagyourtail.unimined.api.spec.DeclaredSource
import xyz.wagyourtail.unimined.api.spec.DeclaredSourceSpec
import xyz.wagyourtail.unimined.api.spec.LoaderSpec
import xyz.wagyourtail.unimined.api.spec.ProductSpec
import xyz.wagyourtail.unimined.api.spec.Side
import xyz.wagyourtail.unimined.minecraft.internal.RuntimeLoaderSpec
import xyz.wagyourtail.unimined.minecraft.internal.RuntimeProductSpec
import xyz.wagyourtail.unimined.minecraft.internal.RuntimeVersionSpec
import xyz.wagyourtail.unimined.minecraft.spi.ArtifactKey
import xyz.wagyourtail.unimined.minecraft.spi.NamespaceMode
import xyz.wagyourtail.unimined.minecraft.spi.ProviderContext
import java.io.File

/** `conversion` provider: product claims + declared-source (nsss)
 *  materialization; network paths stay out of unit scope. */
class ConversionProviderTest {

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
                Logging.getLogger(ConversionProvider::class.java)
            override val javaToolchains = (project as
                    org.gradle.api.internal.project.ProjectInternal)
                .services.get(
                    org.gradle.jvm.toolchain.JavaToolchainService::class.java
                )
            override val useToolchains = project.providers.provider { true }
            override val offline = true
            override val forceReload = false
            override val refreshDependencies = false
            override val refreshDownloads = false
            override val rebuildArtifacts = false
            override val metadataCacheTtl = null
        }
    }

    private fun spec(
        product: String,
        source: DeclaredSource? = DeclaredSource.LocalFile("dist.jar"),
        version: String = "1.0"
    ) = RuntimeProductSpec(
        environmentId = "main",
        sourceSetName = "main",
        version = RuntimeVersionSpec(version),
        side = Side.CLIENT,
        loader = null,
        product = product,
        declaredSource = source?.let { DeclaredSourceSpec(it, "official", null) }
    )

    /** Stage just enough offline metadata for a BTA library projection —
     *  the product's thin manifest plus the b1.7.3 base document it
     *  inherits. No network or dist jar is needed: [ProductArtifacts.libraries]
     *  reads metadata only. */
    private fun stageBtaMetadata(version: String) {
        val cache = File(projectDir, ".gradle/unimined/native-cache")
        File(cache, "metadata/conversion").mkdirs()
        File(cache, "metadata/versions").mkdirs()
        File(cache, "metadata/conversion/bta-$version.json").writeText(
            """{"id":"v$version","type":"release","libraries":[
                {"name":"net.minecraft:launchwrapper:1.5"},
                {"name":"org.lwjgl.lwjgl:lwjgl:2.9.4-babric.1"}
            ]}""".trimIndent()
        )
        File(cache, "metadata/version-manifest-v2.json").writeText(
            """{"latest":{"release":"b1.7.3","snapshot":"b1.7.3"},
                "versions":[{"id":"b1.7.3","type":"release",
                "url":"https://example.test/b1.7.3.json",
                "time":"-","releaseTime":"-"}]}""".trimIndent()
        )
        File(cache, "metadata/versions/b1.7.3.json").writeText(
            """{"id":"b1.7.3","type":"release","libraries":[
                {"name":"net.minecraft:launchwrapper:1.5"},
                {"name":"org.lwjgl.lwjgl:lwjgl:2.9.0"}
            ]}""".trimIndent()
        )
    }

    @Test
    fun `claims conversion products, rejects others`() {
        val provider = ConversionProvider()
        assertTrue(provider.supports(spec("minecraft:reindev")))
        assertTrue(provider.supports(spec("minecraft:bta")))
        assertTrue(provider.supports(spec("minecraft:nsss")))
        assertFalse(provider.supports(spec("minecraft:native")))
        assertFalse(provider.supports(spec("custom:weird")))
    }

    @Test
    fun `declared-source dist materializes with identity namespace + launch`() {
        File(projectDir, "dist.jar").writeBytes(byteArrayOf(1, 2, 3))
        val artifacts = ConversionProvider()
            .resolve(spec("minecraft:nsss"), ctx())
        val jar = artifacts.artifacts[ArtifactKey.CLIENT]!!.get().asFile
        assertTrue(jar.exists())
        assertEquals(byteArrayOf(1, 2, 3).toList(), jar.readBytes().toList())
        assertEquals(NamespaceMode.IDENTITY, artifacts.namespaceMode.get())
        // nsss rides the base b1.7.3 doc for launch metadata
        assertEquals("b1.7.3", artifacts.launch?.rootId)
    }

    @Test
    fun `missing declared source fails with a clear error`() {
        val artifacts = ConversionProvider()
            .resolve(spec("minecraft:nsss", source = null), ctx())
        assertThrows(GradleException::class.java) {
            artifacts.artifacts[ArtifactKey.CLIENT]!!.get()
        }
    }

    @Test
    fun `bta root document is the per-version manifest`() {
        val artifacts = ConversionProvider()
            .resolve(spec("minecraft:bta"), ctx())
        // rootId keys off spec.version (the dist version)
        assertEquals("bta-1.0", artifacts.launch?.rootId)
    }

    @Test
    fun `bta declares the gson version shaded into the dist`() {
        // Regression: sponge-mixin drags gson 2.2.4 onto the dev runtime;
        // without a managed modern coordinate there is no conflict winner
        // and the stale jar can shadow the dist's embedded 2.10.1 classes.
        listOf("7.3_04", "8.0.1").forEach { version ->
            stageBtaMetadata(version)
            val artifacts = ConversionProvider().resolve(
                spec("minecraft:bta", version = version), ctx()
            )
            val coords = artifacts.libraries.get().map {
                "${it.group}:${it.name}:${it.version}"
            }
            assertTrue(
                "com.google.code.gson:gson:2.10.1" in coords,
                "bta $version libraries were $coords"
            )
        }
    }

}
