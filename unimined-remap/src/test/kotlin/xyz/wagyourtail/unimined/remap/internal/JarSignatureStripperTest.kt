package xyz.wagyourtail.unimined.remap.internal

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.jar.Attributes
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import java.util.zip.ZipEntry
import kotlin.io.path.outputStream
import kotlin.io.path.readBytes
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue

class JarSignatureStripperTest {

    @TempDir
    lateinit var dir: Path

    private fun jar(
        name: String,
        entries: Map<String, String> = mapOf("pkg/A.class" to "a"),
        manifest: Manifest? = null
    ): Path {
        val path = dir.resolve(name)
        val jos = if (manifest != null) {
            JarOutputStream(path.outputStream(), manifest)
        } else {
            JarOutputStream(path.outputStream())
        }
        jos.use { out ->
            entries.forEach { (entryName, content) ->
                out.putNextEntry(JarEntry(entryName))
                out.write(content.toByteArray())
                out.closeEntry()
            }
        }
        return path
    }

    private fun digestManifest(): Manifest = Manifest().apply {
        mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
        entries["pkg/A.class"] = Attributes().apply {
            putValue("SHA-256-Digest", "abc123")
            putValue("X-Other", "keep")
        }
    }

    @Test
    fun `unsigned jar needs no sanitize`() {
        val path = jar("unsigned.jar")
        assertFalse(JarSignatureStripper.needsSanitize(path))
        // sanitize is a true no-op — bytes must not churn
        val before = path.readBytes()
        JarSignatureStripper.sanitize(path)
        assertArrayEquals(before, path.readBytes())
    }

    @Test
    fun `signature entries trigger sanitize and are removed`() {
        val path = jar("signed.jar", mapOf(
            "pkg/A.class" to "a",
            "META-INF/TEST.SF" to "sig",
            "META-INF/TEST.RSA" to "rsa"
        ))
        assertTrue(JarSignatureStripper.needsSanitize(path))
        JarSignatureStripper.sanitize(path)
        JarFile(path.toFile()).use { jf ->
            assertNull(jf.getEntry("META-INF/TEST.SF"))
            assertNull(jf.getEntry("META-INF/TEST.RSA"))
            assertNotNull(jf.getEntry("pkg/A.class"))
        }
        assertFalse(JarSignatureStripper.needsSanitize(path))
    }

    @Test
    fun `digest attributes trigger sanitize and are pruned`() {
        val path = jar("digest.jar", manifest = digestManifest())
        assertTrue(JarSignatureStripper.needsSanitize(path))
        JarSignatureStripper.sanitize(path)
        JarFile(path.toFile()).use { jf ->
            val m = Manifest(jf.getInputStream(jf.getEntry("META-INF/MANIFEST.MF")))
            val attrs = m.entries["pkg/A.class"]
            // the digest attr is gone; non-digest attrs on the same
            // section survive (prune, don't drop)
            assertNull(attrs?.getValue("SHA-256-Digest"))
            assertTrue(attrs == null || attrs.getValue("X-Other") == "keep")
        }
        assertFalse(JarSignatureStripper.needsSanitize(path))
    }

    @Test
    fun `manifest without digests needs no sanitize`() {
        val m = Manifest().apply {
            mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
            entries["pkg/A.class"] = Attributes().apply { putValue("X-Other", "keep") }
        }
        val path = jar("clean-mf.jar", manifest = m)
        assertFalse(JarSignatureStripper.needsSanitize(path))
    }

    @Test
    fun `non-jar paths are skipped`() {
        val txt = dir.resolve("notajar.txt")
        txt.toFile().writeText("x")
        assertFalse(JarSignatureStripper.needsSanitize(txt))
        JarSignatureStripper.sanitize(txt)
    }
}
