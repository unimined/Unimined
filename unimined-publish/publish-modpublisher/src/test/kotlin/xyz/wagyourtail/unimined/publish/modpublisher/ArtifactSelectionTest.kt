package xyz.wagyourtail.unimined.publish.modpublisher

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import java.io.File

/** Plan §11 acceptance: selection rules for upload artifacts. */
class ArtifactSelectionTest {

    private val merged = File("merged.jar")
    private val prod = mapOf("forge" to File("prod-forge.jar"), "fabric" to File("prod-fabric.jar"))

    @Test
    fun `merged artifact wins when present`() {
        assertEquals(listOf(merged), ArtifactSelection.select(merged, prod))
    }

    @Test
    fun `per-loader prod jars when no merged`() {
        assertEquals(prod.values.toList(), ArtifactSelection.select(null, prod))
    }

    @Test
    fun `dev artifacts are never selectable`() {
        assertThrows(IllegalArgumentException::class.java) {
            ArtifactSelection.select(merged, prod, dev = listOf(File("dev.jar")))
        }
    }

}
