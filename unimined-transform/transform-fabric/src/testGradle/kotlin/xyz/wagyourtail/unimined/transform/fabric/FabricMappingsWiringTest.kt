package xyz.wagyourtail.unimined.transform.fabric

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import xyz.wagyourtail.unimined.mapping.MutableMappingTree
import xyz.wagyourtail.unimined.mapping.formats.tiny.v2.TinyV2Reader
import xyz.wagyourtail.unimined.mapping.sink.emit
import xyz.wagyourtail.unimined.transform.fabric.internal.exportLoaderMappings
import java.nio.file.Path
import java.util.zip.ZipFile

/**
 * `exportLoaderMappings` emits `mappings/mappings.tiny` for the
 * fabric-family runtime — which only understands the canonical
 * namespace set (`official`, `clientOfficial`/`serverOfficial`,
 * `intermediary`, `named`). Internal column names are relabeled on
 * emission: `official:<side>` → `<side>Official`, and retroMCP-era
 * bare `client`/`server` columns the same way.
 */
class FabricMappingsWiringTest {

    @TempDir
    lateinit var cacheDir: Path

    private fun tree(headerColumns: List<String>): MutableMappingTree =
        runBlocking {
            val tree = MutableMappingTree()
            TinyV2Reader.read(
                "tiny\t2\t0\t${headerColumns.joinToString("\t")}\n" +
                    "c\ta\tb\tc\n"
            ).emit(tree)
            tree
        }

    private fun emittedHeader(jar: Path): String =
        ZipFile(jar.toFile()).use { zip ->
            zip.getEntry("mappings/mappings.tiny")!!
                .let { zip.getInputStream(it).bufferedReader() }
                .readLine()
        }

    @Test
    fun `official-side tagged columns export as canonical fabric names`() {
        val out = exportLoaderMappings(
            tree(listOf("named", "official:client", "official:server")),
            devNamespace = "named", prodNamespace = "intermediary",
            environmentId = "main", cacheDir = cacheDir
        )
        assertEquals(
            "tiny\t2\t0\tnamed\tclientOfficial\tserverOfficial",
            emittedHeader(out)
        )
    }

    @Test
    fun `bare client server columns export as fabric official names`() {
        // retroMCP-era trees carry literal `client`/`server` obf
        // columns — the runtime must still see clientOfficial/serverOfficial
        val out = exportLoaderMappings(
            tree(listOf("named", "client", "server")),
            devNamespace = "named", prodNamespace = "intermediary",
            environmentId = "main", cacheDir = cacheDir
        )
        assertEquals(
            "tiny\t2\t0\tnamed\tclientOfficial\tserverOfficial",
            emittedHeader(out)
        )
    }
}
