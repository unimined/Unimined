package xyz.wagyourtail.unimined.remap

import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import javax.inject.Inject

/**
 * One input namespace in a multi-source [RemappedViewsSpec].
 *
 * Dependencies in [sourceConfiguration] are remapped from [sourceNamespace]
 * to the view set's common target namespace. This models a common modding
 * pattern where, for example, Fabric mods arrive as `intermediary` while a
 * shared library arrives as `official`, and both compile against `named`.
 */
abstract class RemappedNamespaceSpec @Inject constructor(
    /** Namespace that artifacts in [sourceConfiguration] currently use. */
    val sourceNamespace: String,
    /** Name of the declarable dependency bucket for this namespace. */
    val sourceConfiguration: String
) {

    /**
     * Serialized `MappingSnapshot` for `sourceNamespace -> targetNamespace`.
     * Required when this namespace's pipeline step needs mapping data.
     */
    @get:Optional
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val mappingFile: RegularFileProperty

}
