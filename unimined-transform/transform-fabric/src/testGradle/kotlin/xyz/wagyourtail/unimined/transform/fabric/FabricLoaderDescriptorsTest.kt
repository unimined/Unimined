package xyz.wagyourtail.unimined.transform.fabric

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import xyz.wagyourtail.unimined.transform.loader.LoaderRegistry
import xyz.wagyourtail.unimined.transform.loader.SplitMergeSpec

/**
 * ServiceLoader discovery + split-merge declarations for the
 * fabric-family loader products.
 */
class FabricLoaderDescriptorsTest {

    @Test
    fun `fabric products resolve through the service registry`() {
        for (id in listOf("fabric", "quilt", "legacyfabric", "babric", "ornithe")) {
            val descriptor = LoaderRegistry.resolveBuiltin(id)
            assertNotNull(descriptor, "missing descriptor for $id")
            assertEquals("fabric", descriptor!!.family)
        }
    }

    @Test
    fun `quilt classpath groups use quilt loader's system property`() {
        // Quilt forked fabric-loader's dev grouping mechanism but reads
        // `loader.classPathGroups`; there is no fabric.* compatibility
        // fallback in ClasspathModCandidateFinder/SystemProperties.
        assertEquals(
            "loader.classPathGroups",
            LoaderRegistry.resolveBuiltin("quilt")!!.capabilities["classpathGroups"]
        )
        assertEquals(
            "fabric.classPathGroups",
            LoaderRegistry.resolveBuiltin("fabric")!!.capabilities["classpathGroups"]
        )
        assertEquals(
            "loader.gameJarPath",
            LoaderRegistry.resolveBuiltin("quilt")!!.capabilities["gameJarPath"]
        )
        assertEquals(
            "fabric.gameJarPath",
            LoaderRegistry.resolveBuiltin("fabric")!!.capabilities["gameJarPath"]
        )
    }

    @Test
    fun `every intermediaryCoords row declares the split-era merge`() {
        // 1.x `FabricMinecraftTransformer.merge` remapped each side jar
        // into the shared intermediary column before the member merge —
        // every fabric-family descriptor with a coord-fed upstream
        // source declares the same hop; `markSplitOfficial`'s split-era
        // gate confines it to pre-1.3 versions. Loaders whose prod
        // column relabels upstream `intermediary` during composition
        // (legacyfabric/babric) declare the relabeled target — the
        // composed tree the merge consumes never carries a bare
        // `intermediary` column for them
        val expected = mapOf(
            "fabric" to "intermediary",
            "quilt" to "intermediary",
            "legacyfabric" to "legacyIntermediary",
            "babric" to "babricIntermediary",
        )
        for ((id, column) in expected) {
            assertEquals(
                SplitMergeSpec(listOf("clientOfficial", "serverOfficial"), column),
                LoaderRegistry.resolveBuiltin(id)!!.splitMerge,
                "$id should declare the split-era merge"
            )
        }
    }

    @Test
    fun `ornithe's split comes from the calamus channel not the descriptor`() {
        // the upstreamMappingChannel declares per-side calamus sources
        // itself — a descriptor splitMerge would double-mark
        val descriptor = LoaderRegistry.resolveBuiltin("ornithe")!!
        assertNull(descriptor.splitMerge)
        assertEquals("calamus", descriptor.upstreamMappingChannel)
    }

}
