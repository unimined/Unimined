package xyz.wagyourtail.unimined.remap.internal

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import xyz.wagyourtail.unimined.api.util.MavenCoords
import java.nio.file.Path
import java.util.jar.Attributes
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import kotlin.io.path.outputStream
import kotlin.io.path.readBytes
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue

class NestedJarsAppenderTest {

    @TempDir
    lateinit var dir: Path

    private fun jar(
        name: String,
        entries: Map<String, String>,
        manifest: (Manifest.() -> Unit)? = null,
        method: Int = ZipEntry.DEFLATED
    ): Path {
        val path = dir.resolve(name)
        val m = Manifest().apply {
            mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
            manifest?.invoke(this)
        }
        JarOutputStream(path.outputStream(), m).use { out ->
            entries.forEach { (entryName, content) ->
                val bytes = content.toByteArray()
                out.putNextEntry(ZipEntry(entryName).apply {
                    this.method = method
                    if (method == ZipEntry.STORED) {
                        size = bytes.size.toLong()
                        compressedSize = size
                        crc = java.util.zip.CRC32()
                            .also { it.update(bytes) }.value
                    }
                })
                out.write(bytes)
                out.closeEntry()
            }
        }
        return path
    }

    private fun outerJar(): Path = jar(
        "mod.jar",
        mapOf(
            "fabric.mod.json" to """{"schemaVersion":1,"id":"testmod"}""",
            "com/example/Mod.class" to "bytecode"
        )
    )

    private fun nestedEntry(jar: Path, name: String): ZipEntry? =
        ZipFile(jar.toFile()).use { it.getEntry(name) }

    @Test
    fun `fabric embeds under META-INF jars and updates metadata`() {
        val out = outerJar()
        val dep = jar("dep.jar", mapOf(
            "fabric.mod.json" to """{"schemaVersion":1,"id":"dep"}"""
        ))
        val warnings = NestedJarsAppender.append(
            out,
            listOf(NestedJarsAppender.Input(
                dep, MavenCoords("net.fabricmc", "dep", "1.2.3")
            )),
            "fabric", true, "fabric.mod.json", "jars"
        )
        assertTrue(warnings.isEmpty())
        assertNotNull(nestedEntry(out, "META-INF/jars/dep-1.2.3.jar"))
        ZipFile(out.toFile()).use { zip ->
            val fmj = zip.getInputStream(
                zip.getEntry("fabric.mod.json")
            ).readBytes().decodeToString()
            assertTrue(
                "\"file\":\"META-INF/jars/dep-1.2.3.jar\"" in
                        fmj.replace(" ", ""),
                "fmj should declare the nested jar: $fmj"
            )
        }
    }

    @Test
    fun `recompress rewrites inner jar STORED sorted and epoch-stamped`() {
        val out = outerJar()
        // unsorted entries, deflated
        val dep = jar("dep.jar", mapOf(
            "zeta.txt" to "z", "alpha.txt" to "a",
            "fabric.mod.json" to "{}"
        ))
        NestedJarsAppender.append(
            out,
            listOf(NestedJarsAppender.Input(
                dep, MavenCoords("g", "dep", "1.0")
            )),
            "fabric", true, "fabric.mod.json", "jars"
        )
        ZipFile(out.toFile()).use { outer ->
            val entry = outer.getEntry("META-INF/jars/dep-1.0.jar")
            assertEquals(ZipEntry.DEFLATED, entry.method,
                "outer compresses freshly-STORED inner bytes once")
            assertEquals(NestedJarsAppender.ZIP_EPOCH, entry.time)
            val inner = dir.resolve("inner.jar")
            inner.outputStream().use {
                it.write(outer.getInputStream(entry).readBytes())
            }
            ZipFile(inner.toFile()).use { zip ->
                val names = zip.entries().asSequence()
                    .filter { !it.isDirectory }.map { it.name }.toList()
                assertEquals(
                    listOf("META-INF/MANIFEST.MF", "alpha.txt",
                        "fabric.mod.json", "zeta.txt"),
                    names, "inner entries sorted"
                )
                for (e in zip.entries()) {
                    if (e.isDirectory) continue
                    assertEquals(ZipEntry.STORED, e.method,
                        "${e.name} should be STORED")
                    assertEquals(NestedJarsAppender.ZIP_EPOCH, e.time)
                    assertTrue(e.size > 0 && e.crc != 0L,
                        "STORED needs size+crc")
                }
            }
        }
    }

    @Test
    fun `recompress off embeds bytes verbatim STORED`() {
        val out = outerJar()
        val dep = jar("dep.jar", mapOf(
            "fabric.mod.json" to "{}", "x.txt" to "x"
        ))
        val original = dep.readBytes()
        NestedJarsAppender.append(
            out,
            listOf(NestedJarsAppender.Input(
                dep, MavenCoords("g", "dep", "1.0")
            )),
            "fabric", false, "fabric.mod.json", "jars"
        )
        ZipFile(out.toFile()).use { outer ->
            val entry = outer.getEntry("META-INF/jars/dep-1.0.jar")
            assertEquals(ZipEntry.STORED, entry.method)
            assertTrue(outer.getInputStream(entry).readBytes()
                .contentEquals(original), "verbatim embed")
        }
    }

    @Test
    fun `fabric injects synthesized stub when inner lacks metadata`() {
        val out = outerJar()
        val dep = jar("plain.jar", mapOf("x.txt" to "x"))
        NestedJarsAppender.append(
            out,
            listOf(NestedJarsAppender.Input(
                dep, MavenCoords("com.acme", "plainlib", "2.0")
            )),
            "fabric", false, "fabric.mod.json", "jars"
        )
        ZipFile(out.toFile()).use { outer ->
            val entry = outer.getEntry("META-INF/jars/plainlib-2.0.jar")
            val inner = dir.resolve("stub.jar")
            inner.outputStream().use {
                it.write(outer.getInputStream(entry).readBytes())
            }
            ZipFile(inner.toFile()).use { zip ->
                val fmj = zip.getInputStream(
                    zip.getEntry("fabric.mod.json")
                ).readBytes().decodeToString()
                assertTrue("\"com_acme_plainlib\"" in fmj,
                    "synthesized id should be group_artifact: $fmj")
                assertTrue("\"unimined:generated\":true" in
                        fmj.replace(" ", ""), fmj)
            }
        }
    }

    @Test
    fun `quilt injects quilt mod json and quilt_loader key`() {
        val out = jar("mod.jar", mapOf(
            "quilt.mod.json" to
                    """{"schema_version":1,"quilt_loader":{"id":"testmod"}}"""
        ))
        val dep = jar("dep.jar", mapOf("x.txt" to "x"))
        NestedJarsAppender.append(
            out,
            listOf(NestedJarsAppender.Input(
                dep, MavenCoords("g", "dep", "1.0")
            )),
            "fabric", true, "quilt.mod.json", "quilt_loader.jars"
        )
        ZipFile(out.toFile()).use { outer ->
            val qmj = outer.getInputStream(
                outer.getEntry("quilt.mod.json")
            ).readBytes().decodeToString()
            assertTrue("quilt_loader" in qmj && "jars" in qmj, qmj)
            val inner = dir.resolve("qinner.jar")
            inner.outputStream().use {
                it.write(outer.getInputStream(
                    outer.getEntry("META-INF/jars/dep-1.0.jar")
                ).readBytes())
            }
            ZipFile(inner.toFile()).use { zip ->
                val stub = zip.getInputStream(
                    zip.getEntry("quilt.mod.json")
                ).readBytes().decodeToString()
                assertTrue("\"quilt_loader\"" in stub, stub)
                assertNull(zip.getEntry("fabric.mod.json"))
            }
        }
    }

    @Test
    fun `jarjar embeds STORED and writes metadata json`() {
        val out = jar("mod.jar", mapOf("x.class" to "x"))
        val dep = jar("lib.jar", mapOf("y.class" to "y"))
        NestedJarsAppender.append(
            out,
            listOf(NestedJarsAppender.Input(
                dep, MavenCoords("com.acme", "lib", "3.1"),
                mavenVersionRange = "[3.0,4.0)"
            )),
            "jarjar", true, null, null
        )
        ZipFile(out.toFile()).use { outer ->
            val entry = outer.getEntry("META-INF/jarjar/lib-3.1.jar")
            assertEquals(ZipEntry.STORED, entry.method,
                "jarjar entries must be byte-range readable")
            val meta = outer.getInputStream(
                outer.getEntry("META-INF/jarjar/metadata.json")
            ).readBytes().decodeToString()
            assertTrue("\"com.acme\"" in meta, meta)
            assertTrue("\"lib\"" in meta, meta)
            assertTrue("\"[3.0,4.0)\"" in meta, meta)
            assertTrue("\"3.1\"" in meta, meta)
        }
    }

    @Test
    fun `jarjar metadata matches the neoforged jarjar schema`() {
        val out = jar("mod.jar", mapOf("x.class" to "x"))
        val dep = jar("lib.jar", mapOf("y.class" to "y"))
        NestedJarsAppender.append(
            out,
            listOf(NestedJarsAppender.Input(
                dep, MavenCoords("com.acme", "lib", "3.1"),
                mavenVersionRange = "[3.0,4.0)"
            )),
            "jarjar", true, null, null
        )
        ZipFile(out.toFile()).use { outer ->
            val meta = outer.getInputStream(
                outer.getEntry("META-INF/jarjar/metadata.json")
            ).readBytes().decodeToString()
            // FML's ContainedJarMetadata/ContainedVersion serializers
            // read these exact keys — path is REQUIRED (deserializer
            // NPEs without it) and isObfuscated drives runtime remap
            assertTrue("\"range\"" in meta, meta)
            assertTrue("\"artifactVersion\"" in meta, meta)
            assertTrue("\"path\"" in meta, meta)
            assertTrue("META-INF/jarjar/lib-3.1.jar" in meta, meta)
            assertTrue("\"isObfuscated\":false" in meta, meta)
            assertFalse("mavenVersionRange" in meta, meta)
            assertFalse("jarVersion" in meta, meta)
        }
    }

    @Test
    fun `jarjar stamps FMLModType into the nested manifest`() {
        val out = jar("mod.jar", mapOf("x.class" to "x"))
        val dep = jar("lib.jar", mapOf("y.class" to "y"))
        NestedJarsAppender.append(
            out,
            listOf(NestedJarsAppender.Input(
                dep, MavenCoords("com.acme", "lib", "3.1"),
                fmlModType = "LIBRARY"
            )),
            "jarjar", false, null, null
        )
        ZipFile(out.toFile()).use { outer ->
            val entry = outer.getEntry("META-INF/jarjar/lib-3.1.jar")
            val inner = java.io.File(
                dir.resolve("lib-inner.jar").toUri())
            inner.writeBytes(outer.getInputStream(entry).readBytes())
            java.util.jar.JarFile(inner).use { j ->
                assertEquals("LIBRARY",
                    j.manifest.mainAttributes.getValue("FMLModType"))
                // stamped manifest must sit in the leading entries or
                // JarFile can't see it
                val first = j.entries().nextElement().name
                assertEquals("META-INF/MANIFEST.MF", first)
            }
        }
    }

    @Test
    fun `jarjar isObfuscated detects the Obfuscated-By attribute`() {
        val out = jar("mod.jar", mapOf("x.class" to "x"))
        val dep = jar("obf.jar", mapOf("y.class" to "y"), manifest = {
            mainAttributes.putValue("Obfuscated-By", "searge")
        })
        NestedJarsAppender.append(
            out,
            listOf(NestedJarsAppender.Input(
                dep, MavenCoords("com.acme", "obf", "1.0"))),
            "jarjar", true, null, null
        )
        ZipFile(out.toFile()).use { outer ->
            val meta = outer.getInputStream(
                outer.getEntry("META-INF/jarjar/metadata.json")
            ).readBytes().decodeToString()
            assertTrue("\"isObfuscated\":true" in meta, meta)
        }
    }

    @Test
    fun `jarjar isObfuscated spec override wins over detection`() {
        val out = jar("mod.jar", mapOf("x.class" to "x"))
        val dep = jar("obf.jar", mapOf("y.class" to "y"), manifest = {
            mainAttributes.putValue("Obfuscated-By", "searge")
        })
        NestedJarsAppender.append(
            out,
            listOf(NestedJarsAppender.Input(
                dep, MavenCoords("com.acme", "obf", "1.0"),
                obfuscated = false)),
            "jarjar", true, null, null
        )
        ZipFile(out.toFile()).use { outer ->
            val meta = outer.getInputStream(
                outer.getEntry("META-INF/jarjar/metadata.json")
            ).readBytes().decodeToString()
            assertTrue("\"isObfuscated\":false" in meta, meta)
        }
    }

    @Test
    fun `jarjar synthesizes coords from Automatic-Module-Name`() {
        val out = jar("mod.jar", mapOf("x.class" to "x"))
        val dep = jar("local.jar", mapOf("y.class" to "y"),
            manifest = {
                mainAttributes.putValue(
                    "Automatic-Module-Name", "local.lib")
                mainAttributes.putValue("Implementation-Version", "9.9")
            })
        NestedJarsAppender.append(
            out,
            listOf(NestedJarsAppender.Input(dep, null)),
            "jarjar", true, null, null
        )
        ZipFile(out.toFile()).use { outer ->
            assertNotNull(
                outer.getEntry("META-INF/jarjar/local.lib-9.9.jar"))
            val meta = outer.getInputStream(
                outer.getEntry("META-INF/jarjar/metadata.json")
            ).readBytes().decodeToString()
            assertTrue("\"embedded\"" in meta, meta)
            assertTrue("\"local.lib\"" in meta, meta)
        }
    }

    @Test
    fun `warns on dev-namespace nested jar`() {
        val out = outerJar()
        val dep = jar("dep.jar", mapOf(
            "fabric.mod.json" to "{}"
        ), manifest = {
            mainAttributes.putValue(
                JarLint.NAMESPACE_ATTRIBUTE, "named")
        })
        val warnings = NestedJarsAppender.append(
            out,
            listOf(NestedJarsAppender.Input(
                dep, MavenCoords("g", "dep", "1.0")
            )),
            "fabric", true, "fabric.mod.json", "jars",
            expectedNamespace = "intermediary"
        )
        assertTrue(warnings.any { "named" in it && "intermediary" in it },
            "expected namespace-mismatch warning: $warnings")
    }

    @Test
    fun `duplicate file names dedupe with warning`() {
        val out = outerJar()
        val a = jar("a.jar", mapOf("fabric.mod.json" to "{}"))
        val b = jar("b.jar", mapOf("fabric.mod.json" to "{}"))
        val warnings = NestedJarsAppender.append(
            out,
            listOf(
                NestedJarsAppender.Input(
                    a, MavenCoords("g", "same", "1.0")),
                NestedJarsAppender.Input(
                    b, MavenCoords("h", "same", "1.0"))
            ),
            "fabric", true, "fabric.mod.json", "jars"
        )
        assertTrue(warnings.any { "same-1.0.jar" in it }, "$warnings")
    }

    @Test
    fun `deterministic — same inputs produce byte-identical output`() {
        fun build(): ByteArray {
            val out = jar("mod${'$'}{System.nanoTime()}.jar", mapOf(
                "fabric.mod.json" to """{"id":"m"}"""
            ))
            val dep = jar("dep${'$'}{System.nanoTime()}.jar", mapOf(
                "fabric.mod.json" to "{}", "x.txt" to "x"
            ))
            NestedJarsAppender.append(
                out,
                listOf(NestedJarsAppender.Input(
                    dep, MavenCoords("g", "dep", "1.0"))),
                "fabric", true, "fabric.mod.json", "jars"
            )
            return out.readBytes()
        }
        assertTrue(build().contentEquals(build()),
            "recompressed embeds must be reproducible")
    }
}
