package xyz.wagyourtail.unimined.remap

import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import xyz.wagyourtail.unimined.remap.task.RemapArtifactTask
import javax.inject.Inject

/**
 * DSL spec for one-off artifact remaps — `remap.artifact("name") { }`.
 *
 * Standalone (no Minecraft environment): point [input] at any jar and
 * [mappings] at a mapping file in any UML-recognized format (tiny, umf,
 * srg, tsrg, proguard, mcp, ...) or an unimined mapping bundle. When
 * [from]/[to] are unset the namespaces are autodetected at execution:
 * the column whose class names best match the input jar becomes the
 * source, and the single remaining column becomes the target.
 */
abstract class RemapArtifactSpec @Inject constructor(objects: ObjectFactory) {

    /** The artifact to remap. */
    abstract val input: RegularFileProperty

    /** Where the remapped artifact is written. */
    abstract val output: RegularFileProperty

    /** The mapping file — SerializedMappingData zip, MappingBundle JSON,
     *  or any UML-recognized format (autodetected by content). */
    abstract val mappings: RegularFileProperty

    /** Source namespace; optional when the mapping file's columns can be
     *  autodetected from the input jar's class names. */
    abstract val from: Property<String>

    /** Target namespace; optional when exactly one candidate remains. */
    abstract val to: Property<String>

    /** Extra classpath entries for steps declaring `needsClasspath`. */
    abstract val classpath: ConfigurableFileCollection

}
