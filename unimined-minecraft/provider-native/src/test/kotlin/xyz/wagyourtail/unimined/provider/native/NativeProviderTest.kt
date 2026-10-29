package xyz.wagyourtail.unimined.provider.native

import org.gradle.api.Project
import org.gradle.api.artifacts.dsl.DependencyFactory
import org.gradle.api.file.ProjectLayout
import org.gradle.api.logging.Logging
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ProviderFactory
import org.gradle.process.ExecOperations
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import xyz.wagyourtail.unimined.api.spec.LoaderSpec
import xyz.wagyourtail.unimined.api.spec.MinecraftSpec
import xyz.wagyourtail.unimined.api.spec.MinecraftVersionSpec
import xyz.wagyourtail.unimined.api.spec.Side
import xyz.wagyourtail.unimined.minecraft.spi.ArtifactKey
import xyz.wagyourtail.unimined.minecraft.spi.MinecraftProvider
import xyz.wagyourtail.unimined.minecraft.spi.ProviderContext
import xyz.wagyourtail.unimined.provider.native.download.Downloader
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class NativeProviderTest {

    @TempDir
    lateinit var dir: Path

    private val clientJar = jar(mapOf("client.txt" to "client"))
    private val serverJar = jar(mapOf("server.txt" to "server", "shared.txt" to "server"))

    private fun jar(entries: Map<String, String>): ByteArray {
        val bytes = java.io.ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            entries.forEach { (name, text) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
        }
        return bytes.toByteArray()
    }

    private fun sha1(bytes: ByteArray) = MessageDigest.getInstance("SHA-1")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private fun project(): Project = ProjectBuilder.builder()
        .withProjectDir(dir.toFile())
        .build()

    private fun context(project: Project): ProviderContext {
        val dependencies = Proxy.newProxyInstance(
            DependencyFactory::class.java.classLoader,
            arrayOf(DependencyFactory::class.java)
        ) { _, method, args ->
            when (method.name) {
                "create" -> project.dependencies.create(args!![0].toString())
                else -> throw UnsupportedOperationException("DependencyFactory.${method.name}")
            }
        } as DependencyFactory
        val exec = Proxy.newProxyInstance(
            ExecOperations::class.java.classLoader,
            arrayOf(ExecOperations::class.java)
        ) { _, method, _ -> throw UnsupportedOperationException("ExecOperations.${method.name}") }
                as ExecOperations
        return object : ProviderContext {
            override val layout: ProjectLayout = project.layout
            override val objects: ObjectFactory = project.objects
            override val dependencyFactory: DependencyFactory = dependencies
            override val providers: ProviderFactory = project.providers
            override val exec: ExecOperations = exec
            override val logger = Logging.getLogger(NativeProviderTest::class.java)
        }
    }

    private fun provider(): NativeProvider = NativeProvider { _, cache ->
        Downloader(cacheDir = cache, fetch = { url ->
            when {
                url.endsWith("version_manifest_v2.json") -> manifestJson().toByteArray()
                url == "https://example/version.json" -> versionJson().toByteArray()
                url == "https://example/client.jar" -> clientJar
                url == "https://example/server.jar" -> serverJar
                url == "https://example/assets.json" -> "{\"objects\":{}}".toByteArray()
                else -> error("unexpected URL: $url")
            }
        })
    }

    private fun manifestJson() = """
        {"latest":{"release":"1.20.1","snapshot":"1.20.1"},"versions":[
          {"id":"1.20.1","type":"release","url":"https://example/version.json","time":"t","releaseTime":"t"}
        ]}
    """.trimIndent()

    private fun versionJson() = """
        {
          "id":"1.20.1","type":"release","mainClass":"example.Main",
          "downloads":{
            "client":{"url":"https://example/client.jar","sha1":"${sha1(clientJar)}","size":${clientJar.size}},
            "server":{"url":"https://example/server.jar","sha1":"${sha1(serverJar)}","size":${serverJar.size}}
          },
          "assetIndex":{"id":"1.20.1","url":"https://example/assets.json","sha1":"${sha1("{\"objects\":{}}".toByteArray())}","size":14},
          "libraries":[{"name":"org.example:library:1.0"}]
        }
    """.trimIndent()

    private fun spec(side: Side, loader: LoaderSpec? = null) = object : MinecraftSpec {
        override val version: MinecraftVersionSpec = object : MinecraftVersionSpec {
            override val version = "1.20.1"
        }
        override val side = side
        override val loader = loader
    }

    @Test
    fun `provider supports bare minecraft only`() {
        val native = provider()
        assertTrue(native.supports(spec(Side.CLIENT)))
        val loader = object : LoaderSpec {
            override val name = "fabric"
            override val version = "1.0"
        }
        assertFalse(native.supports(spec(Side.CLIENT, loader)))
    }

    @Test
    fun `provider is service discoverable`() {
        val providers = java.util.ServiceLoader.load(
            MinecraftProvider::class.java,
            MinecraftProvider::class.java.classLoader
        ).toList()
        assertTrue(providers.any { it.name == "native" })
    }

    @Test
    fun `client side stays lazy and materializes dependencies assets and launch metadata`() {
        val project = project()
        val calls = mutableListOf<String>()
        val native = NativeProvider { _, cache ->
            Downloader(cacheDir = cache, fetch = { url ->
                calls += url
                when {
                    url.endsWith("version_manifest_v2.json") -> manifestJson().toByteArray()
                    url == "https://example/version.json" -> versionJson().toByteArray()
                    url == "https://example/client.jar" -> clientJar
                    url == "https://example/assets.json" -> "{\"objects\":{}}".toByteArray()
                    else -> error("unexpected URL: $url")
                }
            })
        }
        val result = native.resolve(spec(Side.CLIENT), context(project))
        assertTrue(calls.isEmpty(), "resolve should only wire providers")
        assertEquals(setOf(ArtifactKey.CLIENT), result.artifacts.keys)

        val clientFile = result.artifacts.getValue(ArtifactKey.CLIENT).get().asFile.toPath()
        assertTrue(Files.exists(clientFile))
        assertEquals(
            listOf(
                "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json",
                "https://example/version.json", "https://example/client.jar"
            ), calls
        )

        val library = result.libraries.get().single()
        assertEquals("org.example", library.group)
        assertEquals("library", library.name)
        assertEquals("1.0", library.version)

        val assetIndex = result.assets.get()
        assertEquals("1.20.1", assetIndex.id)
        assertTrue(Files.exists(assetIndex.file.get().asFile.toPath()))
        assertEquals("example.Main", result.launch!!.root["mainClass"]!!.toString().trim('"'))
        assertNotNull(result.mappings.get())
    }

    @Test
    fun `merged side combines server over client`() {
        val result = provider().resolve(spec(Side.MERGED), context(project()))
        assertEquals(setOf(ArtifactKey.MERGED), result.artifacts.keys)
        val merged = result.artifacts.getValue(ArtifactKey.MERGED).get().asFile
        ZipFile(merged).use { zip ->
            assertEquals("client", zip.getInputStream(zip.getEntry("client.txt")).readBytes().decodeToString())
            assertEquals("server", zip.getInputStream(zip.getEntry("server.txt")).readBytes().decodeToString())
            assertEquals("server", zip.getInputStream(zip.getEntry("shared.txt")).readBytes().decodeToString())
        }
    }

    @Test
    fun `server side exposes only server jar`() {
        val result = provider().resolve(spec(Side.SERVER), context(project()))
        assertEquals(setOf(ArtifactKey.SERVER), result.artifacts.keys)
        val server = result.artifacts.getValue(ArtifactKey.SERVER).get().asFile.toPath()
        ZipFile(server.toFile()).use { zip ->
            assertEquals(
                "server",
                zip.getInputStream(zip.getEntry("server.txt")).readBytes().decodeToString()
            )
        }
    }

}
