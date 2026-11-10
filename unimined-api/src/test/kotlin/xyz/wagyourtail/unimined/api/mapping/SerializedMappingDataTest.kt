package xyz.wagyourtail.unimined.api.mapping

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import xyz.wagyourtail.unimined.mapping.MappingTree
import java.nio.file.Path
import kotlin.io.path.writeText

class SerializedMappingDataTest {

    @TempDir
    lateinit var dir: Path

    /** A JSON file that is not a MappingBundle must fall through to
     *  UML autodetect — parchment is the canonical JSON mapping format
     *  (its reader sniffs the `parchment.json` filename). */
    @Test
    fun `json non-bundle falls through to UML autodetect`() {
        val parchment = dir.resolve("parchment.json").also {
            it.writeText("""{"classes":[{"name":"a/B"}]}""")
        }
        val data = SerializedMappingData.readEither(parchment)
        val tree = data.view(MappingTree::class.java)
        assertNotNull(tree)
        assertTrue(tree!!.namespaces.any { it.name == "source" })
    }

    /** A JSON file that is neither bundle nor UML format surfaces the
     *  bundle parse detail so the failure is debuggable. */
    @Test
    fun `unreadable json reports the bundle parse detail`() {
        val bogus = dir.resolve("bogus.json").also {
            it.writeText("""{"totally":"unrelated"}""")
        }
        val e = assertThrows(IllegalArgumentException::class.java) {
            SerializedMappingData.readEither(bogus)
        }
        assertTrue(e.message!!.contains("not a SerializedMappingData bundle"))
        assertTrue(e.message!!.contains("bundle parse"))
    }

}
