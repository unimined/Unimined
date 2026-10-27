package xyz.wagyourtail.unimined.source.task

import java.io.Serializable

/**
 * Serializable coordinates of one mod artifact [GenModSourcesTask]
 * publishes decompiled sources for — the configuration-cache-safe half
 * of `ResolvedArtifactResult` (file + component identifier).
 */
data class ModSourceSpec(
    /** Module group of the original (un-remapped) dependency. */
    val group: String,
    /** Module artifact id. */
    val artifact: String,
    /** Module version. */
    val version: String,
    /**
     * File name of the transformed sources jar inside the task's
     * `sourceJars` collection — the lookup key, since artifact files
     * are renamed by the transform chain.
     */
    val fileName: String
) : Serializable
