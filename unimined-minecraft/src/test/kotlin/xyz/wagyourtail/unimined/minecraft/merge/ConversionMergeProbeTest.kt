package xyz.wagyourtail.unimined.minecraft.merge

import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists

/** Probes whether each total-conversion product's real client/server
 *  dists survive JarMerger's member-merge — products ship
 *  independently-built forks, not clean obf pairs, so mergeability is
 *  an empirical property baked into `ConversionProduct.mergeable`.
 *  Skips pairs absent from the global native-cache. */
class ConversionMergeProbeTest {

    private val cacheRoot = Path.of(
        System.getProperty("user.home"),
        ".gradle", "caches", "unimined", "native-cache"
    )

    private fun probe(product: String, version: String, expectMergeable: Boolean) {
        val dir = cacheRoot.resolve("$product/$version")
        val client = dir.resolve("client.jar")
        val server = dir.resolve("server.jar")
        if (!client.exists() || !server.exists()) {
            println("[probe] $product-$version: jars absent, skipping")
            return
        }
        val out = Files.createTempDirectory("merge-probe").resolve("merged.jar")
        val ok = try {
            JarMerger.merge(client, server, out)
            true
        } catch (e: Throwable) {
            println("[probe] $product-$version merge FAILED: " +
                    "${e.cause?.javaClass?.simpleName ?: e.javaClass.simpleName}: " +
                    (e.cause?.message ?: e.message))
            false
        }
        println("[probe] $product-$version: " +
                if (ok) "MERGEABLE" else "NOT mergeable")
        org.junit.jupiter.api.Assertions.assertEquals(expectMergeable, ok,
            "$product mergeability changed — update ConversionProduct.mergeable")
    }

    @Test
    fun reindev() = probe("reindev", "2.4.1", expectMergeable = false)

    @Test
    fun bta() = probe("bta", "7.3_04", expectMergeable = true) // merge COMPLETES — but the jar hangs at boot; mergeable flag covers the boot criterion
}
