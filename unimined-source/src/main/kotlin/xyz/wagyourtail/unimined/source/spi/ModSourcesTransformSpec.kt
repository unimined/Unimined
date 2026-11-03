package xyz.wagyourtail.unimined.source.spi

import org.gradle.api.file.FileCollection

/**
 * Registration request backing `genModSources`: the engine transforms
 * jars carrying [fromArtifactType] into sources jars carrying
 * [toArtifactType] via an artifact transform, so decompilation is
 * cacheable per artifact rather than a task per mod dependency.
 */
interface ModSourcesTransformSpec {

    /** Artifact-type token of the dev-namespace mod jars. */
    val fromArtifactType: String

    /** Artifact-type token the produced sources jars carry. */
    val toArtifactType: String

    /** Classpath entries for decompiler reference resolution. */
    val classpath: FileCollection

    /** Engine-specific options — the same map as [DecompileSpec.options]. */
    val options: Map<String, String>

}
