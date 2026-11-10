package xyz.wagyourtail.unimined.adapter.patchbase

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import xyz.wagyourtail.unimined.transform.processor.JarProcessor
import java.nio.file.Files
import java.nio.file.Path

/** Spec cache-key behavior: paths + sha256 must round-trip and track
 *  content so patched env artifacts invalidate correctly. */
class PatchBaseProcessorSpecTest {

    @TempDir
    lateinit var dir: Path

    private val processor = PatchBaseProcessor()

    private fun specContext(jars: List<Path>) = object : JarProcessor.SpecContext {
        override val productionNamespace = "official"
        override fun mods() = emptyList<JarProcessor.ModJarInfo>()
        override fun envArtifacts(name: String) =
            if (name == "patchbase") jars else emptyList()
    }

    @Test
    fun `empty patch set builds no spec`() {
        assertNull(processor.buildSpec(specContext(emptyList())))
    }

    @Test
    fun `spec round-trips and tracks content`() {
        val a = dir.resolve("a.zip"); Files.write(a, byteArrayOf(1, 2, 3))
        val b = dir.resolve("b.zip"); Files.write(b, byteArrayOf(4, 5))
        val spec = processor.buildSpec(specContext(listOf(a, b)))!!

        val payload = processor.serializeSpec(spec)
        val back = processor.deserializeSpec(payload)

        assertEquals(payload, processor.serializeSpec(back))

        // content change -> different payload (cache invalidation)
        Files.write(b, byteArrayOf(9))
        val spec2 = processor.buildSpec(specContext(listOf(a, b)))!!
        assertNotEquals(payload, processor.serializeSpec(spec2))
        assertTrue(payload.contains(a.toAbsolutePath().toString()))
    }

    @Test
    fun `processor is pre-remap and named`() {
        assertEquals("patchbase", processor.name)
        assertTrue(processor.preRemap)
    }

}
