package xyz.wagyourtail.unimined.remap

import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional
import xyz.wagyourtail.unimined.remap.spi.StepSpec

/**
 * DSL spec of the mod-artifact post-remap step — rewrites mod-jar
 * metadata that a pure namespace remap leaves untouched:
 * `kotlin.Metadata` internal names, mixin configs + refmap emission
 * (`*.mixins.json` inputs), and JarModAgent/ClassTransform refmaps
 * (manifest `JarModAgent-Refmaps`).
 *
 * Runs as an extension pass after the namespace `remap` step on the
 * [RemapSurface.JAR] pipeline — engine-agnostic, so a `remap("tiny")`
 * override or a remapped dependency view gets the same treatment as the
 * default `uml` engine. Added implicitly to JAR pipelines containing a
 * remap step; declare explicitly via `jar { modArtifacts() }` to pin an
 * engine or ordering.
 */
class ModArtifactsStepSpec(
    @get:Input @get:Optional override val engine: String? = null
) : StepSpec
