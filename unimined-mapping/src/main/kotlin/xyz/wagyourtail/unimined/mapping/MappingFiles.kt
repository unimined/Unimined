package xyz.wagyourtail.unimined.mapping

import org.gradle.api.logging.Logger
import xyz.wagyourtail.unimined.api.mapping.MappingData
import xyz.wagyourtail.unimined.mapping.internal.MappingResolver
import java.nio.file.Path

/**
 * Read already-materialized mapping files through the provider +
 * composer pipeline, in a standalone context — no `MappingExtension`
 * instance required, so task actions can resolve files→data at
 * execution time (the configuration-cache-safe pattern used by
 * `ProduceMappingBundleTask` and `MigrateMappingsTask`).
 */
fun readMappingFiles(
    files: List<Path>, environmentId: String, logger: Logger,
    globalCacheDir: java.nio.file.Path? = null
): MappingData =
    MappingResolver.readFiles(
        files, MappingResolver.standaloneContext(environmentId, logger, globalCacheDir)
    )
