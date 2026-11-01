package xyz.wagyourtail.unimined.mapping

import xyz.wagyourtail.unimined.api.util.uniminedLogger
import org.gradle.api.logging.Logger
import xyz.wagyourtail.unimined.api.mapping.MappingData
import kotlinx.serialization.json.jsonArray
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
        files, MappingResolver.standaloneContext(environmentId, uniminedLogger(logger), globalCacheDir)
    )

/**
 * Read a serialized `MaterializedSourceSpec` manifest (the same JSON
 * `ProduceMappingBundleTask.sourcesManifest` consumes) through the
 * provider + composer pipeline — applies each source's nsMap/env
 * relabels, which a bare [readMappingFiles] over the same files
 * would drop.
 */
fun readMaterializedManifest(
    manifestJson: String, environmentId: String, logger: Logger,
    globalCacheDir: java.nio.file.Path? = null
): MappingData =
    MappingResolver.readMaterialized(
        kotlinx.serialization.json.Json.parseToJsonElement(manifestJson)
            .jsonArray.map {
                xyz.wagyourtail.unimined.mapping.internal
                    .MaterializedSourceSpec.fromJson(it).materialize()
            },
        MappingResolver.standaloneContext(environmentId, uniminedLogger(logger), globalCacheDir)
    )
