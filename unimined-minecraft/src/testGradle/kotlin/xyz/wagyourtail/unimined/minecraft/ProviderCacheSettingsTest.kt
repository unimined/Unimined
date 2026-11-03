package xyz.wagyourtail.unimined.minecraft

import org.gradle.api.GradleException
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import xyz.wagyourtail.unimined.minecraft.internal.ProviderCacheSettings
import java.time.Duration

class ProviderCacheSettingsTest {

    @Test
    fun `forceReload is broad alias while controls stay distinct`() {
        val legacy = ProviderCacheSettings.parse(
            offline = false,
            refreshDependencies = false,
            forceReloadValue = "true",
            refreshDownloadsValue = null,
            rebuildArtifactsValue = null,
            metadataTtlValue = null
        )
        assertTrue(legacy.forceReload)
        assertTrue(legacy.refreshUniminedDownloads)
        assertTrue(legacy.rebuildUniminedArtifacts)

        val gradleRefresh = ProviderCacheSettings.parse(
            offline = false,
            refreshDependencies = true,
            forceReloadValue = null,
            refreshDownloadsValue = null,
            rebuildArtifactsValue = null,
            metadataTtlValue = null
        )
        assertFalse(gradleRefresh.forceReload)
        assertTrue(gradleRefresh.refreshDependencies)
        assertTrue(gradleRefresh.refreshUniminedDownloads)
        assertTrue(gradleRefresh.rebuildUniminedArtifacts)

        val downloadsOnly = ProviderCacheSettings.parse(
            offline = false,
            refreshDependencies = false,
            forceReloadValue = null,
            refreshDownloadsValue = "true",
            rebuildArtifactsValue = null,
            metadataTtlValue = null
        )
        assertTrue(downloadsOnly.refreshUniminedDownloads)
        assertFalse(downloadsOnly.rebuildUniminedArtifacts)
    }

    @Test
    fun `offline flag and metadata ttl are exposed`() {
        val settings = ProviderCacheSettings.parse(
            offline = true,
            refreshDependencies = false,
            forceReloadValue = "false",
            refreshDownloadsValue = null,
            rebuildArtifactsValue = null,
            metadataTtlValue = "PT6H"
        )
        assertTrue(settings.offline)
        assertEquals(Duration.ofHours(6), settings.metadataCacheTtl)
        assertFalse(settings.forceReload)
    }

    @Test
    fun `invalid property values fail clearly`() {
        assertThrows<GradleException> {
            ProviderCacheSettings.parse(false, false, "yes", null, null, null)
        }
        val error = assertThrows<GradleException> {
            ProviderCacheSettings.parse(false, false, null, null, null, "six hours")
        }
        assertTrue(error.message!!.contains("unimined.metadataCacheTtl"))
    }

}
