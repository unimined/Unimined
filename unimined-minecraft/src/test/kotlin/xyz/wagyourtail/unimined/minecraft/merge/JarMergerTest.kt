package xyz.wagyourtail.unimined.minecraft.merge

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldNode
import org.objectweb.asm.tree.InnerClassNode
import org.objectweb.asm.tree.MethodNode
import java.nio.file.Path
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.io.path.outputStream

class JarMergerTest {

    @TempDir
    lateinit var dir: Path

    private fun jar(path: Path, entries: Map<String, String>) {
        ZipOutputStream(path.outputStream()).use { z ->
            for ((n, c) in entries) {
                z.putNextEntry(java.util.zip.ZipEntry(n))
                z.write(c.toByteArray())
                z.closeEntry()
            }
        }
    }

    private fun jar(path: Path, classes: List<ClassNode>) {
        ZipOutputStream(path.outputStream()).use { z ->
            for (node in classes) {
                z.putNextEntry(java.util.zip.ZipEntry("${node.name}.class"))
                val w = ClassWriter(0)
                node.accept(w)
                z.write(w.toByteArray())
                z.closeEntry()
            }
        }
    }

    private fun classNode(
        name: String,
        methods: List<String> = emptyList(),
        fields: List<String> = emptyList(),
        annotations: List<String> = emptyList(),
        inner: InnerClassNode? = null
    ) = ClassNode().apply {
        version = Opcodes.V1_8
        access = Opcodes.ACC_PUBLIC
        this.name = name
        superName = "java/lang/Object"
        methods.mapTo(this.methods) {
            MethodNode(Opcodes.ACC_PUBLIC, it, "()V", null, null)
        }
        fields.mapTo(this.fields) {
            FieldNode(Opcodes.ACC_PUBLIC, it, "I", null, null)
        }
        if (annotations.isNotEmpty()) {
            visibleAnnotations = annotations.mapTo(mutableListOf()) {
                org.objectweb.asm.tree.AnnotationNode(it)
            }
        }
        inner?.let { innerClasses.add(it) }
    }

    private fun readNode(jar: Path, name: String): ClassNode {
        ZipFile(jar.toFile()).use { z ->
            val bytes = z.getInputStream(z.getEntry("$name.class")).readBytes()
            return ClassNode().also { ClassReader(bytes).accept(it, 0) }
        }
    }

    private fun entryNames(jar: Path): Set<String> =
        ZipFile(jar.toFile()).use { z -> z.entries().asSequence().map { it.name }.toSet() }

    @Test
    fun `merge combines entries, client wins on conflict`() {
        val client = dir.resolve("client.jar")
        val server = dir.resolve("server.jar")
        jar(client, mapOf("a.txt" to "client-a", "shared.txt" to "client"))
        jar(server, mapOf("b.txt" to "server-b", "shared.txt" to "server"))

        val out = dir.resolve("merged.jar")
        JarMerger.merge(client, server, out)

        ZipFile(out.toFile()).use { z ->
            assertEquals("client-a", z.getInputStream(z.getEntry("a.txt")).readBytes().decodeToString())
            assertEquals("server-b", z.getInputStream(z.getEntry("b.txt")).readBytes().decodeToString())
            assertEquals("client", z.getInputStream(z.getEntry("shared.txt")).readBytes().decodeToString())
        }
    }

    @Test
    fun `server-bundled library entries are stripped, client kept verbatim`() {
        val client = dir.resolve("client.jar")
        val server = dir.resolve("server.jar")
        val lib = dir.resolve("lib.jar")
        jar(
            client, mapOf(
                "net/minecraft/Foo.class" to "mc-client",
                // client may legitimately carry a same-path class — a
                // whitelist would drop it; membership strip only reads the
                // server side
                "com/mojang/renderpearl/Thing.class" to "renderpearl"
            )
        )
        jar(
            server, mapOf(
                "net/minecraft/Bar.class" to "mc-server",
                "com/google/common/Foo.class" to "bundled-guava",
                "it/unimi/dsi/fastutil/IntList.class" to "bundled-fastutil",
                "org/example/custom/ServerOnly.class" to "server-only"
            )
        )
        jar(
            lib, mapOf(
                "com/google/common/Foo.class" to "guava",
                "it/unimi/dsi/fastutil/IntList.class" to "fastutil"
            )
        )

        val out = dir.resolve("merged.jar")
        JarMerger.merge(client, server, out, JarMerger.Options(libraryJars = listOf(lib)))

        assertEquals(
            setOf(
                "net/", "net/minecraft/", "com/", "com/mojang/",
                "com/mojang/renderpearl/", "org/", "org/example/",
                "org/example/custom/",
                "net/minecraft/Foo.class",
                "com/mojang/renderpearl/Thing.class",
                "net/minecraft/Bar.class",
                "org/example/custom/ServerOnly.class"
            ), entryNames(out)
        )
    }

    @Test
    fun `exclude and include globs override membership strip`() {
        val client = dir.resolve("client.jar")
        val server = dir.resolve("server.jar")
        val lib = dir.resolve("lib.jar")
        jar(client, mapOf("a.class" to "a"))
        jar(
            server, mapOf(
                "b.class" to "b",
                "lib/Lib.class" to "lib-copy",
                "org/mozilla/rhino/Script.class" to "undeclared-bundled",
                "lib/Keep.class" to "keep-me"
            )
        )
        jar(
            lib, mapOf(
                "lib/Lib.class" to "lib",
                "lib/Keep.class" to "lib"
            )
        )

        val out = dir.resolve("merged.jar")
        JarMerger.merge(
            client, server, out, JarMerger.Options(
                libraryJars = listOf(lib),
                includeGlobs = listOf("lib/Keep.class"),
                excludeGlobs = listOf("org/mozilla/**")
            )
        )

        assertEquals(setOf("lib/", "a.class", "b.class", "lib/Keep.class"), entryNames(out))
    }

    @Test
    fun `merge output is byte-deterministic`() {
        // the merged jar is a transform INPUT — entry timestamps must
        // not vary between runs or the downstream artifact-transform
        // cache misses on every produce
        val client = dir.resolve("client.jar")
        val server = dir.resolve("server.jar")
        jar(client, mapOf("a/Foo.class" to "client-foo", "shared.txt" to "client"))
        jar(server, mapOf("b/Bar.class" to "server-bar", "shared.txt" to "server"))

        val out1 = dir.resolve("merged1.jar")
        val out2 = dir.resolve("merged2.jar")
        JarMerger.merge(client, server, out1)
        Thread.sleep(1100) // cross a DOS-time tick
        JarMerger.merge(client, server, out2)

        org.junit.jupiter.api.Assertions.assertArrayEquals(
            java.nio.file.Files.readAllBytes(out1),
            java.nio.file.Files.readAllBytes(out2)
        )
    }

    @Test
    fun `strip output is byte-deterministic`() {
        val input = dir.resolve("input.jar")
        val lib = dir.resolve("lib.jar")
        jar(input, mapOf("game/Main.class" to "game", "lib/Lib.class" to "bundled"))
        jar(lib, mapOf("lib/Lib.class" to "lib"))

        val out1 = dir.resolve("stripped1.jar")
        val out2 = dir.resolve("stripped2.jar")
        BundledLibraryStrip.strip(input, listOf(lib), out1)
        Thread.sleep(1100)
        BundledLibraryStrip.strip(input, listOf(lib), out2)

        org.junit.jupiter.api.Assertions.assertArrayEquals(
            java.nio.file.Files.readAllBytes(out1),
            java.nio.file.Files.readAllBytes(out2)
        )
    }

    @Test
    fun `strip disabled keeps bundled library entries`() {
        val client = dir.resolve("client.jar")
        val server = dir.resolve("server.jar")
        val lib = dir.resolve("lib.jar")
        jar(client, mapOf("a.class" to "a"))
        jar(server, mapOf("lib/Lib.class" to "bundled"))
        jar(lib, mapOf("lib/Lib.class" to "lib"))

        val out = dir.resolve("merged.jar")
        JarMerger.merge(
            client, server, out, JarMerger.Options(
                libraryJars = listOf(lib),
                stripBundledServerLibraries = false
            )
        )

        assertEquals(setOf("lib/", "a.class", "lib/Lib.class"), entryNames(out))
    }

    @Test
    fun `standalone strip drops bundled members, keeps manifest and services`() {
        val input = dir.resolve("input.jar")
        val lib = dir.resolve("lib.jar")
        jar(lib, mapOf(
            "META-INF/MANIFEST.MF" to "lib-manifest",
            "META-INF/services/spi.Provider" to "lib.Provider",
            "org/slf4j/LoggerFactory.class" to "lib",
            "org/slf4j/spi/Only.class" to "lib",
        ))
        jar(input, mapOf(
            "META-INF/MANIFEST.MF" to "dist-manifest",
            "META-INF/services/spi.Provider" to "dist.Provider",
            "game/Main.class" to "game",
            "org/slf4j/LoggerFactory.class" to "bundled",
            "org/slf4j/spi/Only.class" to "bundled",
        ))

        val out = dir.resolve("stripped.jar")
        BundledLibraryStrip.strip(input, listOf(lib), out)

        val entries = entryNames(out)
        // bundled lib classes dropped — emptied package dirs are gone too
        assertEquals(
            setOf("META-INF/", "META-INF/MANIFEST.MF",
                "META-INF/services/", "META-INF/services/spi.Provider",
                "game/", "game/Main.class"),
            entries
        )
        // input's own manifest + service file survive — membership
        // can't strip non-library-code entries
        java.util.zip.ZipFile(out.toFile()).use {
            assertEquals("dist-manifest",
                it.getInputStream(
                    it.getEntry("META-INF/MANIFEST.MF")).reader().readText())
            assertEquals("dist.Provider",
                it.getInputStream(
                    it.getEntry("META-INF/services/spi.Provider")).reader().readText())
        }
    }

    private val marker = JarMerger.SideMarker.FORGE_FML
    private val markerDesc = "L${marker.annotation};"

    private fun env(node: ClassNode): String? =
        node.visibleAnnotations?.firstOrNull { it.desc == markerDesc }
            ?.values?.let { (it[it.indexOf("value") + 1] as Array<*>)[1] as String }

    @Test
    fun `side markers stamp single-side classes and members`() {
        val client = dir.resolve("client.jar")
        val server = dir.resolve("server.jar")
        jar(client, listOf(
            classNode("a/Only", methods = listOf("c")),
            classNode("a/Shared", methods = listOf("both", "cOnly"),
                fields = listOf("bothF", "cOnlyF"))
        ))
        jar(server, listOf(
            classNode("b/Only", methods = listOf("s")),
            classNode("a/Shared", methods = listOf("both", "sOnly"),
                fields = listOf("bothF", "sOnlyF"))
        ))

        val out = dir.resolve("merged.jar")
        JarMerger.merge(client, server, out,
            JarMerger.Options(sideMarkers = listOf(marker)))

        assertEquals("CLIENT", env(readNode(out, "a/Only")))
        assertEquals("SERVER", env(readNode(out, "b/Only")))
        val shared = readNode(out, "a/Shared")
        assertEquals(null, env(shared)) // both sides — no class mark
        assertEquals("CLIENT",
            shared.methods.first { it.name == "cOnly" }
                .visibleAnnotations.first { it.desc == markerDesc }
                .values.let { (it[it.indexOf("value") + 1] as Array<*>)[1] })
        assertEquals("SERVER",
            shared.fields.first { it.name == "sOnlyF" }
                .visibleAnnotations.first { it.desc == markerDesc }
                .values.let { (it[it.indexOf("value") + 1] as Array<*>)[1] })
        assertTrue(shared.methods.first { it.name == "both" }
            .visibleAnnotations?.none { it.desc == markerDesc } != false)
    }

    @Test
    fun `side markers skip anonymous classes and pre-annotated content`() {
        val client = dir.resolve("client.jar")
        val server = dir.resolve("server.jar")
        jar(client, listOf(classNode("a/Outer")))
        jar(server, listOf(
            // anonymous: self InnerClasses entry with null innerName
            classNode("a/Outer$1",
                inner = InnerClassNode("a/Outer$1", null, null, 0)),
            // already carries the marker — no duplicate stamp
            classNode("b/Marked", annotations = listOf(markerDesc))
        ))

        val out = dir.resolve("merged.jar")
        JarMerger.merge(client, server, out,
            JarMerger.Options(sideMarkers = listOf(marker)))

        val anon = readNode(out, "a/Outer$1")
        assertTrue(anon.visibleAnnotations?.none { it.desc == markerDesc }
            ?: true, "anonymous class must not be side-marked")
        val marked = readNode(out, "b/Marked")
        assertEquals(1,
            marked.visibleAnnotations.count { it.desc == markerDesc })
    }

    @Test
    fun `detectSideMarker finds the era the loader jar ships`() {
        val jar = dir.resolve("loader.jar")
        jar(jar, mapOf(
            "cpw/mods/fml/relauncher/SideOnly.class" to "stub",
            "cpw/mods/fml/relauncher/Side.class" to "stub"
        ))
        assertEquals(JarMerger.SideMarker.FORGE_CPMODS,
            JarMerger.SideMarker.detectSideMarker(jar))

        val none = dir.resolve("nomarker.jar")
        jar(none, mapOf("x/Y.class" to "y"))
        var warned = ""
        assertEquals(null, JarMerger.SideMarker
            .detectSideMarker(none) { warned = it })
        assertTrue(warned.isNotEmpty())
    }

}
