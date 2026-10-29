package xyz.wagyourtail.unimined.transform.processor

import org.gradle.api.logging.Logger
import org.gradle.api.logging.Logging
import xyz.wagyourtail.unimined.api.mapping.MappingContext
import xyz.wagyourtail.unimined.api.mapping.MappingData
import xyz.wagyourtail.unimined.api.mapping.MappingPostProcessor
import xyz.wagyourtail.unimined.mapping.MappingTree

/**
 * Mapping-side half of an environment's declared [JarProcessor]s —
 * bridges each processor's `processMappings` into mapping resolution
 * as a spec-scoped [MappingPostProcessor] (runs after the global
 * registry when the env spec composes).
 *
 * Processor declarations and specs evaluate inside [process] — wiring
 * order between transform plugins doesn't matter, and mod jars only
 * resolve when mappings do.
 */
class JarProcessorsMappingsPostProcessor(
    private val processors: () -> List<JarProcessor>,
    private val specContext: (String?) -> JarProcessor.SpecContext
) : MappingPostProcessor {

    override val name = "jar-processor-mappings"

    override fun process(data: MappingData, ctx: MappingContext): MappingData {
        val active = processors()
            .mapNotNull { p -> p.processMappings()?.let { m -> p to m } }
        if (active.isEmpty()) return data

        val specContext = specContext(
            data.view(MappingTree::class.java)?.namespaces?.firstOrNull()?.name
        )
        var result = data
        for ((processor, mappings) in active) {
            val spec = processor.buildSpec(specContext) ?: continue
            val context = object : JarProcessor.ProcessorContext {
                override val logger: Logger = Logging.getLogger(JarProcessorsMappingsPostProcessor::class.java)
                override val mappings: MappingData get() = result
                override val mappingContext: MappingContext get() = ctx
                override fun mods() = specContext.mods()
            }
            result = mappings.transform(result, spec, context)
        }
        return result
    }

}
