package xyz.wagyourtail.unimined.mapping

import java.util.ServiceLoader
import xyz.wagyourtail.unimined.api.mapping.MappingContext
import xyz.wagyourtail.unimined.api.mapping.MappingData
import xyz.wagyourtail.unimined.api.mapping.MappingPostProcessor

/**
 * Ordered [MappingPostProcessor] list applied to composed mapping data.
 * `META-INF/services` implementations load first; [register] appends —
 * remove or reorder [processors] to customize the stage order.
 */
class MappingPostProcessorRegistry {

    /** The ordered stage list — fold order, left to right. */
    val processors: MutableList<MappingPostProcessor> = mutableListOf()

    init {
        val found = ServiceLoader.load(
            MappingPostProcessor::class.java,
            MappingPostProcessor::class.java.classLoader
        ).toList() + ServiceLoader.load(
            MappingPostProcessor::class.java,
            Thread.currentThread().contextClassLoader
        ).toList()
        found.distinctBy { it.name }.forEach(processors::add)
    }

    /** Append a post-processor stage. */
    fun register(processor: MappingPostProcessor) {
        processors.add(processor)
    }

    /** Run every stage in order, threading each output into the next. */
    fun process(data: MappingData, ctx: MappingContext): MappingData =
        processors.fold(data) { acc, p -> p.process(acc, ctx) }

}
