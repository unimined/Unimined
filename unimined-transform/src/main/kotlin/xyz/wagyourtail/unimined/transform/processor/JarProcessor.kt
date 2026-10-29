package xyz.wagyourtail.unimined.transform.processor

import org.gradle.api.logging.Logger
import xyz.wagyourtail.commons.core.logger.Logger as CommonsLogger
import xyz.wagyourtail.unimined.api.mapping.MappingContext
import xyz.wagyourtail.unimined.api.mapping.MappingData
import xyz.wagyourtail.unimined.api.util.uniminedLogger
import java.io.Serializable
import java.nio.file.Path

/**
 * A Minecraft-jar processor (Loom `MinecraftJarProcessor` parity) —
 * declared per environment by transform plugins, applied to the
 * dev-Minecraft artifact as a JAR-surface pipeline step, and optionally
 * to the composed mappings.
 *
 * Lifecycle:
 *  1. [buildSpec] — lazily, at execution/fingerprint time, over the
 *     declared mod batch and environment settings. `null` = inactive
 *     (no step work, no cache-key contribution).
 *  2. [serializeSpec]/`[deserializeSpec]` — the spec's deterministic
 *     payload is an input of the pipeline step, so spec changes
 *     invalidate the processed-Minecraft artifact.
 *  3. [processJar] — jar mutation, run inside the `jar-processors`
 *     pipeline step in declaration order.
 *  4. [processMappings] — optional mapping mutation, run through the
 *     environment's mapping-enrichment channel after composition.
 */
interface JarProcessor {

    /** Stable impl id — diagnostics and spec-payload keys. */
    val name: String

    /**
     * Build this processor's deterministic spec over [context], or
     * `null` when it has nothing to do (empty spec set = step no-ops).
     */
    fun buildSpec(context: SpecContext): JarProcessorSpec?

    /** Serialize [spec] to its deterministic cache-key payload. */
    fun serializeSpec(spec: JarProcessorSpec): String

    /** Inverse of [serializeSpec], called at execution. */
    fun deserializeSpec(payload: String): JarProcessorSpec

    /** Apply the jar mutation described by [spec] to [jar] in place. */
    fun processJar(jar: Path, spec: JarProcessorSpec, context: ProcessorContext)

    /**
     * The optional mapping mutation — invoked on the composed
     * [MappingData] via the environment's post-processor channel.
     */
    fun processMappings(): MappingsProcessor? = null

    /**
     * A processor's deterministic description of its work — mod-ids,
     * entry paths, content hashes. Its serialized form is the step's
     * cache key, so the content must capture everything that affects
     * the output.
     */
    interface JarProcessorSpec : Serializable

    /**
     * Lazy view of the declared mod batch and environment settings for
     * [buildSpec] — evaluated at execution, when mod jars are resolved.
     */
    interface SpecContext {

        /** The environment's production namespace. */
        val productionNamespace: String

        /** Mods on the compile+runtime views, in resolution order. */
        fun mods(): List<ModJarInfo>

    }

    /**
     * One resolved mod jar — metadata access is lazy and loader-typed;
     * [custom] reads loader-specific metadata (fabric's `custom`
     * section) when the loader wiring supplied a metadata-aware impl.
     */
    interface ModJarInfo {

        /** The resolved jar. */
        val jar: Path

        /** Loader-declared mod id, when known. */
        val id: String?

        /** Loader-specific metadata key, e.g. `loom:provided_javadoc`. */
        fun custom(key: String): String?

        /** Read a jar entry, or `null` when absent. */
        fun readEntry(path: String): ByteArray?

    }

    /** Execution context for [processJar]/[MappingsProcessor.transform]. */
    interface ProcessorContext {

        val logger: Logger

        /** Env-scoped structured logger — prefer over raw [logger] so
         *  processor output carries the `[Unimined/<env>/…]` path. */
        val log: CommonsLogger
            get() = uniminedLogger(logger)

        /** Composed mappings when the pipeline step needs them. */
        val mappings: MappingData?

        /**
         * The mapping-resolution context — present only on the
         * mappings-processor path (the jar step has none).
         */
        val mappingContext: MappingContext?
            get() = null

        /** The resolved mod batch (same view as [SpecContext.mods]). */
        fun mods(): List<ModJarInfo>

    }

    /** The optional mapping-side half of a processor. */
    fun interface MappingsProcessor {

        /**
         * Mutate (or replace) the composed [data] — return the data to
         * hand downstream, following `MappingPostProcessor` semantics.
         */
        fun transform(
            data: MappingData,
            spec: JarProcessorSpec,
            context: ProcessorContext
        ): MappingData

    }

}
