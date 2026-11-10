package xyz.wagyourtail.unimined.transform

import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class BundledResourceChannelTest {

    @TempDir lateinit var dir: File

    private fun project() = ProjectBuilder.builder()
        .withProjectDir(dir).build()

    @Test
    fun `materialize extracts the resource under the env dir`() {
        val res = BundledResource("/bundled-test/payload.bin")
        val out = BundledResourceChannel.materialize(
            project(), "main", res, BundledResourceChannelTest::class.java)
        assertEquals("unimined/bundled/main/payload.bin",
            out.relativeTo(File(dir, "build")).path.replace('\\', '/'))
        assertArrayEquals("bundled-payload".toByteArray(), out.readBytes())
    }

    @Test
    fun `missing resource reports the anchor and path`() {
        val e = assertThrows(GradleException::class.java) {
            BundledResourceChannel.materialize(
                project(), "main", BundledResource("/nope.bin"),
                BundledResourceChannelTest::class.java)
        }
        assert(e.message!!.contains("/nope.bin"))
    }
}
