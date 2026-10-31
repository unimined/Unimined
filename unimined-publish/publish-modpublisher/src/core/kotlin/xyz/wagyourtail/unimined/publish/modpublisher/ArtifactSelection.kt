package xyz.wagyourtail.unimined.publish.modpublisher

import java.io.File

/**
 * Plan §11 upload-artifact selection: prefer the loader-merge merged
 * production artifact when one exists; otherwise the per-loader
 * production jars. Dev artifacts are never selectable.
 */
object ArtifactSelection {

    /**
     * @param merged the `merged` artifact from unimined-mod-loader-merge, if produced
     * @param perLoaderProd loader name → production (remapped) jar
     * @param dev dev artifacts — always excluded; presence is an error signal
     */
    fun select(
        merged: File?,
        perLoaderProd: Map<String, File>,
        dev: List<File> = emptyList()
    ): List<File> {
        require(dev.isEmpty()) {
            "dev artifacts are never uploadable through modpublisher: $dev"
        }
        return if (merged != null) listOf(merged) else perLoaderProd.values.toList()
    }

}
